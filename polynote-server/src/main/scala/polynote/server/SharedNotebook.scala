package polynote.server

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import cats.Monad
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.{Concurrent, ContextShift, IO}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.syntax.traverse._
import cats.instances.list._
import cats.instances.option._
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue, SignallingRef, Topic}
import polynote.config.PolynoteConfig
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel._
import polynote.kernel.util._
import polynote.messages._
import polynote.runtime.{StreamingDataRepr, TableOp, UpdatingDataRepr}
import polynote.server.SharedNotebook.{GlobalVersion, SubscriberId}
import polynote.util.VersionBuffer
import scodec.bits.ByteVector

import scala.collection.JavaConverters._

/**
  * SharedNotebook is responsible for managing concurrent access and updates to a notebook. It's the central authority
  * for the canonical serialization of edits to the notebook. Eventually, it should resolve conflicts in concurrent edits
  * and broadcast changes to subscribers.
  *
  * Here's the current idea for how this would work (though it's not completely implemented):
  *
  * The notebook has a "global" (server) version number, and each client has a "local" (client) version number. When
  * a client sends an edit to the server, it includes the latest global version it knows about, and its local version,
  * which it then increments. When the server broadcasts an edit, it sends a message to each subscriber containing
  * the global version of the edit, and the local version to which it applies for that client. Then, the client can
  * transform the edit (if necessary) to its current local version, and it will be in a consistent state with the
  * global version.
  *
  * Similarly, the server can do part of the transformation work – when it receives an edit that acts upon global version
  * X, but it knows that client A is currently on at least version X + 3, it can transform the edit to act upon
  * version X + 3 and send that transformed edit to client A (which will also apply any necessary transformations locally).
  *
  * Thus, the SharedNotebook must track its global version, the current notebook, and low watermark of the highest global version
  * that all clients have acknowledged. It must also keep a history buffer of all edits between the low watermark and
  * the current global version. Clients can periodically send a status message to the server indicating the current-known
  * global version and the current local version, in order to allow the server to discard edit history.
  *
  * Clients must track the currently known global version, which updates whenever the client receives a foreign edit,
  * and the highest local version which has been acknowledged by the server (either by sending an edit based on it,
  * or through an acknowledgement message). It has to keep its local edit history between that known-acknowledged
  * version and its current version, to allow rebasing new foreign edits.
  *
  * It sounds a lot more complicated than it really is.
  */
trait SharedNotebook[F[_]] {

  def path: String

  /**
    * Open a reference to this shared notebook.
    *
    * @param name A string identifying who is opening the notebook (i.e. their username or email)
    * @return A [[NotebookRef]] which the caller can use to send updates to the shared notebook
    */
  def open(name: String): F[NotebookRef[F]]

  def versions: Stream[F, (GlobalVersion, Notebook)]

  def shutdown(): F[Unit]
}

object SharedNotebook {

  // aliases for disambiguating tuple members
  type SubscriberId = Int
  type GlobalVersion = Int
}


class IOSharedNotebook(
  val path: String,
  ref: SignallingRef[IO, (GlobalVersion, Notebook)],            // the Int is the global version, which can wrap around back to zero if necessary
  kernelRef: Ref[IO, Option[KernelAPI[IO]]],
  notebookContext: SignallingRef[IO, NotebookContext],
  updates: Queue[IO, Option[(SubscriberId, NotebookUpdate, Deferred[IO, GlobalVersion])]],   // the canonical series of edits
  updatesTopic: Topic[IO, Option[(GlobalVersion, SubscriberId, NotebookUpdate)]],  // a subscribe-able channel for watching updates,
  kernelFactory: KernelFactory[IO],
  outputMessages: Topic[IO, Message],
  kernelLock: Semaphore[IO],
  config: PolynoteConfig
)(implicit contextShift: ContextShift[IO]) extends SharedNotebook[IO] {

  private val shutdownSignal: ReadySignal = ReadySignal()

  private val updateBuffer = new VersionBuffer[NotebookUpdate]

  private val statusUpdates = Publish.PublishTopic(outputMessages).contramap[KernelStatusUpdate](KernelStatus(ShortString(path), _))

  def notebookIO(): IO[Notebook] = ref.get.map(_._2)

  def shutdown(): IO[Unit] = for {
    _         <- subscribers.values().asScala.toList.parTraverse(_.close())
    kernelOpt <- kernelRef.get
    _         <- kernelOpt.map(_.shutdown()).sequence
    _         <- shutdownSignal.complete
  } yield ()

  // listen to the stream of updates and apply them in order, each one incrementing the global version
  updates.dequeue.unNoneTerminate.zipWithIndex.evalMap {
    case ((subscriberId, update, versionPromise), version) =>
      val newGlobalVersion = (version % Int.MaxValue).toInt + 1
      updateBuffer.add(newGlobalVersion, update)
      applyUpdate(newGlobalVersion, subscriberId, update, versionPromise)
  }.interruptWhen(shutdownSignal()).compile.drain.unsafeRunAsyncAndForget()


  private def ensureKernel(): IO[KernelAPI[IO]] = kernelLock.acquire.bracket { _ =>
    kernelRef.get.flatMap {
      case None => kernelFactory.launchKernel(() => ref.get.map(_._2), notebookContext, statusUpdates, config).flatMap {
        kernel =>
          kernelRef.set(Some(kernel)).as(kernel)
      }
      case Some(kernel) => IO.pure(kernel)
    }
  }(_ => kernelLock.release)

  // apply a versioned update from the queue, completing its version promise and updating the info about which
  // versions the originating subscriber knows about
  // TODO: should the `ref.get` be replaced with a `ref.update`?
  private def applyUpdate(newGlobalVersion: Int, subscriberId: Int, update: NotebookUpdate, versionPromise: Deferred[IO, GlobalVersion]) = ref.get.flatMap {
    case (currentVer, notebook) =>

      // TODO: remove this, just checking for now
      assert(newGlobalVersion - 1 == currentVer, "Version is wrong!")

      val doUpdate = ref.set(newGlobalVersion -> update.applyTo(notebook)).flatMap {
        _ =>
//          kernelRef.get.flatMap {
//            case None => IO.unit
//            case Some(kernel) => kernel.updateNotebook(newGlobalVersion, update)
//          }
//      }.flatMap { _ =>
          update match {
            case InsertCell(_, _, _, cell, after) => CellContext(cell.id).flatMap {
              cellContext => notebookContext.update {
                nbCtx =>
                  nbCtx.insert(cellContext, Option(after))
                  nbCtx
              }
            }
            case DeleteCell(_, _, _, id) => notebookContext.update {
              nbCtx =>
                nbCtx.remove(id)
                nbCtx
            }
            case _ => IO.unit
          }
      }

      for {
        _  <- doUpdate
        sub = subscribers.get(subscriberId)
        _  <- if (sub != null) sub.setKnownVersions(update.globalVersion, update.localVersion) else IO.unit
        _  <- updatesTopic.publish1(Some((newGlobalVersion, subscriberId, update)))
        _  <- versionPromise.complete(newGlobalVersion).attempt
      } yield ()
  }

  // enqueue an update and return a promise for the global version that will eventually represent that update
  private def submitUpdate(subscriberId: SubscriberId, update: NotebookUpdate): IO[Deferred[IO, GlobalVersion]] = for {
    versionPromise <- Deferred[IO, GlobalVersion]
    _              <- updates.enqueue1(Some((subscriberId, update, versionPromise)))
  } yield versionPromise

  private def transformUpdate(update: NotebookUpdate, toVersion: GlobalVersion): NotebookUpdate = {
    updateBuffer.getRange(update.globalVersion, toVersion).foldLeft(update) {
      case (accum, (ver, next)) => accum.rebase(next)
    }
  }

  private val subscribers = new ConcurrentHashMap[Int, Subscriber]()
  private val nextSubscriberId = new AtomicInteger(0)

  def open(name: String): IO[NotebookRef[IO]] = for {
    subscriberId    <- IO(nextSubscriberId.getAndIncrement())
    foreignUpdates   = updatesTopic.subscribe(1024).unNone.filter(_._2 != subscriberId)
    currentNotebook <- ref.get
    subscriber       = new Subscriber(subscriberId, name, foreignUpdates, currentNotebook._1)
    _               <- IO { subscribers.put(subscriberId, subscriber); () }
    _               <- subscriber.init()
  } yield subscriber

  def versions: Stream[IO, (GlobalVersion, Notebook)] = ref.discrete

  private def withInterpreterLaunch[A](cell: NotebookCell)(fn: KernelAPI[IO] => IO[A]): IO[A] = for {
    kernel        <- ensureKernel()
    predefResults <- kernel.startInterpreterFor(cell)
    _             <- predefResults.map(result => CellResult(ShortString(path), -1, result)).through(outputMessages.publish).compile.drain
    result        <- fn(kernel)
  } yield result

  /**
    * If the [[ResultValue]] has any [[UpdatingDataRepr]]s, create a [[SignallingRef]] to capture its updates in a
    * Stream. When the finalizer of the repr is run, the stream will terminate.
    */
  private def watchUpdatingValues(value: ResultValue) = {
    value.reprs.collect {
      case updating: UpdatingDataRepr => updating
    } match {
      case Nil => Stream.empty
      case updatingReprs =>
        Stream.emits(updatingReprs).evalMap {
          repr => SignallingRef[IO, Option[Option[ByteVector32]]](Some(None)).flatMap {
            ref => IO {
              UpdatingDataRepr.getHandle(repr.handle)
                .getOrElse(throw new IllegalStateException("Created UpdatingDataRepr handle not found"))
                .setUpdater {
                  buf =>
                    val b = buf.duplicate()
                    b.rewind()
                    ref.set(Some(Some(ByteVector32(ByteVector(b))))).unsafeRunSync()
                }.setFinalizer {
                  () => ref.set(None).unsafeRunSync()
                }
            } as {
              ref.discrete.unNoneTerminate.unNone.map {
                update => HandleData(ShortString(path), Updating, repr.handle, 1, Array(update))
              }
            }
          }
        }.flatten
    }
  }

  /**
    * - Launch the interpreter for the cell, if necessary
    * - Create a queue to stream back the results to the Subscriber who queued the cell
    * - Queue the cell in the kernel
    * - Tap the resulting stream into a window buffer as well as the broadcast topic, so other clients will get the results
    * - At each result, update the notebook with the buffered results
    * - Start the stream and run it independently of the requesting subscriber. A copy of the stream will be returned
    *   to the caller through the indirection of the created Queue, so the evaluation of effects won't depend on whether
    *   the caller is listening.
    */
  private def queueCell(cell: NotebookCell): IO[IO[Stream[IO, Result]]] =
    cell match {
      case textCell if textCell.language == "text" => IO.pure(IO.pure(Stream.empty))
      case _ =>
        withInterpreterLaunch(cell) {
          kernel =>
            Queue.unbounded[IO, Option[Result]].flatMap {
              resultsOut =>
                val buf = new WindowBuffer[Result](1000)
                val queueSome = new EnqueueSome(resultsOut)

                def updateNotebookResults() = ref.update {
                  case (ver, nb) =>
                    val bufList = buf.toList
                    val execInfo = bufList.collect {
                      case executionInfo: ExecutionInfo => executionInfo
                    }.lastOption
                    val newMetadata = cell.metadata.copy(executionInfo = execInfo)

                    ver -> nb.setResults(cell.id, bufList).setMetadata(cell.id, newMetadata)
                }

                kernel.queueCell(cell).map {
                  ioResult =>
                    ioResult.flatMap {
                      results =>
                        results.evalTap {
                          result => IO(buf.add(result)) *> outputMessages.publish1(CellResult(ShortString(path), cell.id, result))
                        }.evalTap {
                          // if there are any UpdatingDataReprs, watch for their updates and broadcast
                          case v: ResultValue => watchUpdatingValues(v).through(outputMessages.publish).compile.drain.start.as(())
                          case _ => IO.unit
                        }.evalTap {
                          _ => updateNotebookResults()
                        }.onFinalize {
                          updateNotebookResults() *> resultsOut.enqueue1(None)
                        }.through(queueSome.enqueue).compile.drain.start.as(resultsOut.dequeue.unNoneTerminate)
                    }.handleErrorWith(ErrorResult.toStream)
                }
            }
        }.uncancelable
  }

  class Subscriber(
    subscriberId: Int,
    name: String,
    foreignUpdates: Stream[IO, (GlobalVersion, SubscriberId, NotebookUpdate)],
    initialVersion: GlobalVersion
  ) extends NotebookRef[IO] {
    private val lastLocalVersion = new AtomicInteger(0)
    private val lastGlobalVersion = new AtomicInteger(initialVersion)

    private val closeSignal = ReadySignal()

    def setKnownVersions(global: Int, local: Int): IO[Unit] = IO {
      lastGlobalVersion.set(global)
      lastLocalVersion.set(local)
    }

    def lastKnownGlobalVersion: Int = lastGlobalVersion.get()

    val messages: Stream[IO, Message] = Stream.emits(Seq(
      outputMessages.subscribe(1024),
      foreignUpdates.interruptWhen(closeSignal()).evalMap {
        case (globalVersion, _, update) =>
          val knownGlobalVersion = lastGlobalVersion.get()

          if (globalVersion < knownGlobalVersion) {
            // this edit should come before other edits I've already seen - transform it up to knownGlobalVersion
            IO.pure(Some(transformUpdate(update, knownGlobalVersion).withVersions(knownGlobalVersion, lastLocalVersion.get())))
          } else if (globalVersion > knownGlobalVersion) {
            // this edit should come after the last global version I've seen - client will transform locally if necessary
            IO.pure(Some(update.withVersions(globalVersion, lastLocalVersion.get())))
          } else {
            // already know about this version
            IO.pure(None)
          }
      }.unNone.evalTap {
        update => IO(lastLocalVersion.incrementAndGet()).as(()) // this update will increment their local version
      }.covaryOutput[Message])).parJoinUnbounded.interruptWhen(closeSignal())


    val path: String = IOSharedNotebook.this.path

    override def get: IO[Notebook] = notebookIO()

    override def update(update: NotebookUpdate): IO[Int] = {
      for {
        versionPromise <- submitUpdate(subscriberId, update)
        version        <- versionPromise.get
      } yield version
    }

    override def close(): IO[Unit] = for {
      _ <- closeSignal.complete
      _ <- IO(subscribers.remove(subscriberId))
    } yield ()

    override def isKernelStarted: IO[Boolean] = kernelRef.get.map(_.nonEmpty)


    override def shutdown(): IO[Unit] = kernelLock.acquire.bracket { _ =>
      kernelRef.get.flatMap {
        case None => IO.unit
        case Some(kernel) => kernel.shutdown().flatMap {
          _ => kernelRef.set(None)
        }
      }
    }(_ => kernelLock.release)

    // we want to queue all the cells, but evaluate them in order. So the outer IO of the result runs the outer IO of queueCell for all the cells.
    override def runCells(cells: List[NotebookCell]): IO[Stream[IO, Result]] =
      cells.map {
        id => queueCell(id)
      }.sequence.flatMap {
        queued =>
          queued.map(_.start).sequence.map {
            fibers => Stream.emits(fibers).evalMap(_.join).flatten
          }
      }

    def startKernel(): IO[Unit] = ensureKernel().as(())

    def init(): IO[Unit] = notebookIO().flatMap {
      notebook => if (notebook.cells.isEmpty) IO.unit else {
        notebook.cells.map {
          cell => CellContext(cell.id).flatMap(context => notebookContext.update {
            ctx =>
              ctx.insertLast(context)
              ctx
          })
        }.sequence.as(())
      }
    }

    private def ifKernelStarted[A](yes: KernelAPI[IO] => IO[A], no: => A): IO[A] = isKernelStarted.flatMap {
      case true  => ensureKernel().flatMap(yes)
      case false => IO(no)
    }

    def startInterpreterFor(cell: NotebookCell): IO[Stream[IO, Result]] = ensureKernel().flatMap(_.startInterpreterFor(cell))

    override def runCell(cell: NotebookCell): IO[Stream[IO, Result]] = queueCell(cell).flatten

    def queueCell(cell: NotebookCell): IO[IO[Stream[IO, Result]]] = IOSharedNotebook.this.queueCell(cell)

    def completionsAt(cell: NotebookCell, pos: GlobalVersion): IO[List[Completion]] =
      withInterpreterLaunch(cell)(_.completionsAt(cell, pos))

    def parametersAt(cell: NotebookCell, offset: GlobalVersion): IO[Option[Signatures]] =
      withInterpreterLaunch(cell)(_.parametersAt(cell, offset))

    def currentSymbols(): IO[List[ResultValue]] = ifKernelStarted(_.currentSymbols(), Nil)

    def currentTasks(): IO[List[TaskInfo]] = ifKernelStarted(_.currentTasks(), Nil)

    def idle(): IO[Boolean] = ifKernelStarted(_.idle(), false)

    override def info: IO[Option[KernelInfo]] = ifKernelStarted(_.info, None)

    private val streams = new StreamingHandleManager

    override def getHandleData(handleType: HandleType, handleId: Int, count: Int): IO[Array[ByteVector32]] = handleType match {
      case Streaming => streams.getStreamData(handleId, count)
      case _ => ensureKernel().flatMap(_.getHandleData(handleType, handleId, count))
    }

    override def releaseHandle(handleType: HandleType, handleId: GlobalVersion): IO[Unit] = handleType match {
      case Lazy | Updating => ensureKernel().flatMap(_.releaseHandle(handleType, handleId))
      case Streaming =>
        IO(streams.releaseStreamHandle(handleId)) *> ensureKernel().flatMap(_.releaseHandle(Streaming, handleId))
    }

    override def modifyStream(handleId: Int, ops: List[TableOp]): IO[Option[StreamingDataRepr]] =
      ensureKernel().flatMap(_.modifyStream(handleId, ops))

    override def cancelTasks(): IO[Unit] = ifKernelStarted(_.cancelTasks(), ())

//    override def updateNotebook(version: GlobalVersion, update: NotebookUpdate): IO[Unit] = IO.unit

    override def clearOutput(): IO[Stream[IO, CellResult]] = {
      ref.update {
        case (ver, nb) =>
          val updatedNb = nb.cells.map(_.id).foldLeft(nb) {
            (updatedNb, id) => updatedNb.setResults(id, Nil)
          }
          ver -> updatedNb
      } *> get.map { nb =>
        Stream.emits(nb.cells.map(_.id)).map { id =>
          CellResult(nb.path, id, ClearResults())
        }
      }
    }
  }

}

object IOSharedNotebook {
  def apply(
    path: String,
    initial: Notebook,
    kernelFactory: KernelFactory[IO],
    config: PolynoteConfig)(implicit
    contextShift: ContextShift[IO]
  ): IO[IOSharedNotebook] = for {
    ref             <- SignallingRef[IO, (GlobalVersion, Notebook)](0 -> initial)
    kernel          <- Ref[IO].of[Option[KernelAPI[IO]]](None)
    notebookContext <- SignallingRef[IO, NotebookContext](new NotebookContext)
    updates         <- Queue.unbounded[IO, Option[(SubscriberId, NotebookUpdate, Deferred[IO, GlobalVersion])]]
    updatesTopic    <- Topic[IO, Option[(GlobalVersion, SubscriberId, NotebookUpdate)]](None)
    outputMessages  <- Topic[IO, Message](KernelStatus(ShortString(path), KernelBusyState(busy = false, alive = false)))
    kernelLock      <- Semaphore[IO](1)
  } yield new IOSharedNotebook(path, ref, kernel, notebookContext, updates, updatesTopic, kernelFactory, outputMessages, kernelLock, config)
}

abstract class NotebookRef[F[_]](implicit F: Monad[F]) extends KernelAPI[F] {

  def path: String

  def get: F[Notebook]

  /**
    * Apply an update to the notebook
    * @return The global version after the update was applied
    */
  def update(update: NotebookUpdate): F[Int]

  /**
    * Close this reference to the shared notebook
    */
  def close(): F[Unit]

  def isKernelStarted: F[Boolean]

  def startKernel(): F[Unit]

  def currentStatus: F[KernelBusyState] = isKernelStarted.flatMap {
    case true => for {
      idle   <- idle()
    } yield KernelBusyState(!idle, alive = true)

    case false => Monad[F].pure(KernelBusyState(busy = false, alive = false))
  }

  def restartKernel(): F[Unit] = isKernelStarted.flatMap {
    case true => shutdown() *> startKernel()
    case false => F.unit
  }

  def messages: Stream[F, Message]

  /**
    * Clear all outputs
    */
  def clearOutput(): F[Stream[F, CellResult]]
}