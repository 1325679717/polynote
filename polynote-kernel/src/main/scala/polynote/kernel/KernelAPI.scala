package polynote.kernel

import cats.effect.concurrent.Ref
import fs2.Stream
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import polynote.messages.{ByteVector32, CellID, CellResult, HandleType, NotebookCell, NotebookUpdate, ShortString, TinyList, TinyMap, TinyString}
import polynote.runtime.{StreamingDataRepr, TableOp}
import scodec.codecs.{Discriminated, Discriminator, byte}
import scodec.codecs.implicits._
import scodec.{Attempt, Codec, Err}

import scala.reflect.internal.util.Position
import scala.util.Try
import shapeless.cachedImplicit

/**
  * The Kernel is expected to reference cells by their notebook ID; it must have a way to access the [[polynote.messages.Notebook]].
  *
  * @see [[polynote.kernel.lang.LanguageInterpreter]]
  */
trait KernelAPI[F[_]] {

  /**
    * Initialize this kernel
    */
  def init(): F[Unit]

  /**
    * Shutdown this kernel
    */
  def shutdown(): F[Unit]

  /**
    * Start the language interpreter needed for the given cell
    */
  def startInterpreterFor(cell: NotebookCell): F[Stream[F, Result]]

  /**
    * Run the given cell.
    * @return A [[Stream]] of [[Result]] values which result from running the cell, inside of an [[F]] effect. The effect
    *         represents the cell execution starting such that it can begin a stream of results.
    */
  def runCell(cell: NotebookCell): F[Stream[F, Result]]

  /**
    * Queue the cell - the outer F represents the queueing of the cell, while the inner F represents the cell execution
    * starting such that it can begin a stream of results.
    *
    * @return A [[Stream]] of [[Result]] values which result from running the cell, inside of two layers of [[F]] effect.
    *         The outer layer represents queueing the cell, while the inner layer represents defining the cell's result
    *         stream.
    */
  def queueCell(cell: NotebookCell): F[F[Stream[F, Result]]]

  /**
    * Run multiple cells, combining their result streams into one stream of [[CellResult]] messages
    */
  def runCells(cells: List[NotebookCell]): F[Stream[F, Result]]

  /**
    * @return Completion candidates at the given position within the given cell
    */
  def completionsAt(cell: NotebookCell, pos: Int): F[List[Completion]]

  /**
    * @return Optional parameter hints at the given position within the given cell
    */
  def parametersAt(cell: NotebookCell, pos: Int): F[Option[Signatures]]

  /**
    * @return A list of currently defined [[ResultValue]]s in the notebook's most recent execution state
    */
  def currentSymbols(): F[List[ResultValue]]

  /**
    * @return A list of currently incomplete tasks
    */
  def currentTasks(): F[List[TaskInfo]]

  /**
    * @return A boolean indicating whether the kernel is currently idle. False if the kernel is currently doing some
    *         work.
    */
  def idle(): F[Boolean]

  /**
    * @return An optional [[KernelInfo]] structure, which surfaces general slowly-changing information about the kernel's state
    *         (for example, the URL to the Spark UI for Spark-enabled kernels)
    */
  def info: F[Option[KernelInfo]]

  /**
    * @return An array of up to `count` [[scodec.bits.ByteVector]] elements, in which each element represents one encoded
    *         element from the given handle of the given type
    */
  def getHandleData(handleType: HandleType, handle: Int, count: Int): F[Array[ByteVector32]]

  /**
    * Create a new [[StreamingDataRepr]] handle by performing [[TableOp]] operations on the given streaming handle. The
    * given handle itself must be unaffected.
    *
    * @return If the operations make no changes, returns the given handle. If the operations are valid for the stream,
    *         and it supports the modification, returns a new handle for the modified stream. If the stream doesn't support
    *         modification, returns None. If the modifications are invalid or unsupported by the the stream, it may either
    *         raise an error or return None.
    */
  def modifyStream(handleId: Int, ops: List[TableOp]): F[Option[StreamingDataRepr]]

  /**
    * Release a handle. No further data will be available using [[getHandleData()]].
    */
  def releaseHandle(handleType: HandleType, handleId: Int): F[Unit]

  /**
    * Cancel all the currently running tasks
    */
  def cancelTasks(): F[Unit]

//  /**
//    * Notify the kernel of a notebook update. This may do nothing if the kernel already has access to the changing
//    * notebook state, but it will be invoked whenever a [[NotebookUpdate]] occurs; kernels can use this to keep a
//    * synchronized copy of the notebook if needed.
//    *
//    * @see [[NotebookUpdate.applyTo()]]
//    */
//  def updateNotebook(version: Int, update: NotebookUpdate): F[Unit]

}

// TODO: should just make a codec for Position? To avoid extra object?
// TODO: can't recover line number from just this info. Should we store that?
final case class Pos(sourceId: String, start: Int, end: Int, point: Int) {
  def this(position: Position) = this(
    position.source.toString,
    if (position.isDefined) Try(position.start).getOrElse(-1) else -1,
    if (position.isDefined) Try(position.end).getOrElse(-1) else -1,
    position.pointOrElse(-1)
  )
}

object Pos {
  implicit val encoder: Encoder[Pos] = deriveEncoder[Pos]
  implicit val decoder: Decoder[Pos] = deriveDecoder[Pos]
}

final case class KernelReport(pos: Pos, msg: String, severity: Int) {
  def severityString: String = severity match {
    case KernelReport.Info => "Info"
    case KernelReport.Warning => "Warning"
    case _ => "Error"
  }

  override def toString: String =
    s"""$severityString: $msg (${pos.start})""" // TODO: line and column instead
}

object KernelReport {
  final val Info = 0
  final val Warning = 1
  final val Error = 2
  implicit val encoder: Encoder[KernelReport] = deriveEncoder[KernelReport]
  implicit val decoder: Decoder[KernelReport] = deriveDecoder[KernelReport]
}

sealed trait CompletionType

object CompletionType {
  final case object Term extends CompletionType
  final case object Field extends CompletionType
  final case object Method extends CompletionType
  final case object Package extends CompletionType
  final case object TraitType extends CompletionType
  final case object ClassType extends CompletionType
  final case object Module extends CompletionType
  final case object TypeAlias extends CompletionType
  final case object Keyword extends CompletionType
  final case object Unknown extends CompletionType

  // these were chosen to line up with LSP, for no reason other than convenience with Monaco
  val fromByte: PartialFunction[Byte, CompletionType] = {
    case 0  => Unknown
    case 5  => Term
    case 4  => Field
    case 1  => Method
    case 18 => Package
    case 7  => TraitType
    case 6  => ClassType
    case 8  => Module
    case 17 => TypeAlias
    case 13 => Keyword
  }

  def toByte(typ: CompletionType): Byte = typ match {
    case Unknown   => 0
    case Term      => 5
    case Field     => 4
    case Method    => 1
    case Package   => 18
    case TraitType => 7
    case ClassType => 6
    case Module    => 8
    case TypeAlias => 17
    case Keyword   => 13
  }

  implicit val codec: Codec[CompletionType] = scodec.codecs.byte.exmap(
    fromByte.lift andThen (opt => Attempt.fromOption[CompletionType](opt, Err("Invalid completion type number"))),
    toByte _ andThen Attempt.successful
  )
}

final case class Completion(
  name: TinyString,
  typeParams: TinyList[TinyString],
  paramLists: TinyList[TinyList[(TinyString, ShortString)]],
  resultType: ShortString,
  completionType: CompletionType)

final case class ParameterHint(
  name: TinyString,
  typeName: TinyString,
  docString: Option[ShortString])

final case class ParameterHints(
  name: TinyString,
  docString: Option[ShortString],
  parameters: TinyList[ParameterHint])

final case class Signatures(
  hints: TinyList[ParameterHints],
  activeSignature: Byte,
  activeParameter: Byte)

sealed trait KernelStatusUpdate

object KernelStatusUpdate {
  implicit val discriminated: Discriminated[KernelStatusUpdate, Byte] = Discriminated(byte)
  implicit val codec: Codec[KernelStatusUpdate] = cachedImplicit
}

abstract class KernelStatusUpdateCompanion[T <: KernelStatusUpdate](id: Byte) {
  implicit val discriminator: Discriminator[KernelStatusUpdate, T, Byte] = Discriminator(id)
}

final case class SymbolInfo(
  name: TinyString,
  typeName: TinyString,
  valueText: TinyString,
  availableViews: TinyList[TinyString])

final case class UpdatedSymbols(
  newOrUpdated: TinyList[SymbolInfo],
  removed: TinyList[TinyString]
) extends KernelStatusUpdate

object UpdatedSymbols extends KernelStatusUpdateCompanion[UpdatedSymbols](0)

sealed trait TaskStatus
object TaskStatus {
  final case object Complete extends TaskStatus
  final case object Queued extends TaskStatus
  final case object Running extends TaskStatus
  final case object Error extends TaskStatus

  val fromByte: PartialFunction[Byte, TaskStatus] = {
    case 0 => Complete
    case 1 => Running
    case 2 => Queued
    case 3 => Error
  }

  def toByte(taskStatus: TaskStatus): Byte = taskStatus match {
    case Complete => 0
    case Running => 1
    case Queued => 2
    case Error => 3
  }

  implicit val codec: Codec[TaskStatus] = byte.exmap(
    fromByte.lift andThen (Attempt.fromOption(_, Err("Invalid task status byte"))),
    s => Attempt.successful(toByte(s))
  )
}

final case class TaskInfo(
  id: TinyString,
  label: TinyString,
  detail: ShortString,
  status: TaskStatus,
  progress: Byte = 0) {

  def running: TaskInfo = copy(status = TaskStatus.Running)
  def completed: TaskInfo = copy(status = TaskStatus.Complete, progress = 255.toByte)
}

final case class UpdatedTasks(
  tasks: TinyList[TaskInfo]
) extends KernelStatusUpdate

object UpdatedTasks extends KernelStatusUpdateCompanion[UpdatedTasks](1)

final case class KernelBusyState(busy: Boolean, alive: Boolean) extends KernelStatusUpdate
object KernelBusyState extends KernelStatusUpdateCompanion[KernelBusyState](2)

//                                           key          html
final case class KernelInfo(content: TinyMap[ShortString, String]) extends KernelStatusUpdate {
  def combine(other: KernelInfo): KernelInfo = {
    copy(TinyMap(content ++ other.content))
  }
}
object KernelInfo extends KernelStatusUpdateCompanion[KernelInfo](3) {
  def apply(tups: (String, String)*): KernelInfo = KernelInfo(TinyMap(tups.map {
    case (k, v) => ShortString(k) -> v
  }.toMap))
}

final case class ExecutionStatus(cellID: CellID, pos: Option[(Int, Int)]) extends KernelStatusUpdate
object ExecutionStatus extends KernelStatusUpdateCompanion[ExecutionStatus](4)