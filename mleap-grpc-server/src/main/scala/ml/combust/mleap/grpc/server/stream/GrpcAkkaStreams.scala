package ml.combust.mleap.grpc.server.stream

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage._
import io.grpc.stub.StreamObserver

import scala.collection.mutable

object GrpcAkkaStreams {
  def source[O]: Source[O, StreamObserver[O]] = Source.fromGraph(new GrpcSourceStage[O])
  def sink[I](observer: StreamObserver[I],
              closeOnComplete: Boolean = true): Sink[I, NotUsed] = Sink.fromGraph(new GrpcSinkStage[I](observer, closeOnComplete))

  class GrpcSourceStage[O]() extends GraphStageWithMaterializedValue[SourceShape[O], StreamObserver[O]] {
    val out: Outlet[O] = Outlet[O]("grpc.out")

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, StreamObserver[O]) = {
      var observer: StreamObserver[O] = null

      val logic = new GraphStageLogic(shape) {
        var started: Boolean = false
        val buffer: mutable.Queue[O] = mutable.Queue[O]()

        def handleError(t: Throwable): Unit = {
          fail(out, t)
        }

        def handleCompleted(): Unit = {
          complete(out)
        }

        def handleNext(value: O): Unit = {
          if (started) {
            emit(out, value)
          } else {
            buffer += value
          }
        }

        observer = new StreamObserver[O] {
          override def onError(t: Throwable): Unit = getAsyncCallback((_: Unit) => handleError(t)).invoke(())
          override def onCompleted(): Unit = getAsyncCallback((_: Unit) => handleCompleted()).invoke(())
          override def onNext(value: O): Unit = getAsyncCallback((value: O) => handleNext(value)).invoke(value)
        }

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (buffer.nonEmpty) {
              emitMultiple(out, buffer.dequeueAll(_ => true).iterator)
            }
          }
        })

        override def preStart(): Unit = {
          started = true
        }
      }

      (logic, observer)
    }

    override def shape: SourceShape[O] = SourceShape.of(out)
  }

  class GrpcSinkStage[I](observer: StreamObserver[I],
                         closeOnComplete: Boolean = true) extends GraphStage[SinkShape[I]] {
    val in: Inlet[I] = Inlet[I]("grpc.in")

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      new GraphStageLogic(shape) {
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            observer.onNext(grab(in))
          }

          override def onUpstreamFinish(): Unit = {
            if (closeOnComplete) { observer.onCompleted() }
          }

          override def onUpstreamFailure(ex: Throwable): Unit = observer.onError(ex)
        })
      }
    }

    override def shape: SinkShape[I] = SinkShape.of[I](in)
  }
}
