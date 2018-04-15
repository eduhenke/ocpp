package com.thenewmotion.ocpp
package json
package api

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import org.json4s.JValue
import org.slf4j.LoggerFactory

/**
 * The middle layer in the three-layer protocol stack of OCPP-J: Simple Remote
 * Procedure Call.
 *
 * The SRPC layer relates WebSocket messages to each other as calls,
 * call results and call errors.
 *
 * Although the OCPP 1.6 specification no longer uses the term "SRPC", it is
 * present in the original specification on
 * http://www.gir.fr/ocppjs/ocpp_srpc_spec.shtml, which is referenced by the
 * IANA WebSocket Subprotocol Name Registry at
 * https://www.iana.org/assignments/websocket/websocket.xml. So bureaucracy is
 * on our side in naming this.
 */
trait SrpcComponent {
  trait SrpcConnection {
    /**
      * Send an outgoing call to the remote endpoint
      * @param msg The outgoing call
      * @return The incoming response, asynchronously
      */
    def sendCall(msg: SrpcCall): Future[SrpcResponse]

    /**
      * Close the connection.
      *
      * This will allow all pending incoming calls to be responded to,
      * and only then will close the underlying WebSocket.
      *
      * @return A future that completes when connection has indeed closed
      */
    def close(): Future[Unit]

    /**
      * Close the connection, without waiting for the call handler to handle
      * calls that were already received.
      *
      * @return
      */
    def forceClose(): Unit
  }

  def srpcConnection: SrpcConnection

  /**
    * To be overridden to handle incoming calls
    *
    * @param msg The incoming call
    * @return The outgoing message, asynchronously
    */
  def onSrpcCall(msg: SrpcCall): Future[SrpcResponse]

  /**
    * Called when the SRPC connection closes.
    */
  def onSrpcDisconnect(): Unit
}

trait DefaultSrpcComponent extends SrpcComponent {
  this: WebSocketComponent =>

  import DefaultSrpcComponent._

  private[this] val logger = LoggerFactory.getLogger(DefaultSrpcComponent.this.getClass)

  private[this] val callIdGenerator = CallIdGenerator()

  implicit val executionContext: ExecutionContext

  class DefaultSrpcConnection extends SrpcConnection {

    private var state: ConnectionState = Open

    private val callIdCache: mutable.Map[String, Promise[SrpcResponse]] =
        mutable.Map.empty[String, Promise[SrpcResponse]]

    /** The number of incoming calls received that we have not yet responded to */
    private var numIncomingCalls: Int = 0

    private var closePromise: Option[Promise[Unit]] = None

    def close(): Future[Unit] = synchronized {
      state match {
        case Open =>
          val p = Promise[Unit]()
          closePromise = Some(p)
          if (numIncomingCalls == 0) {
            executeGracefulClose()
            state = Closed
          } else {
            state = Closing
          }
          p.future
        case Closing | Closed =>
          throw new IllegalStateException("Connection already closed")
      }
    }

    def forceClose(): Unit = synchronized {
      webSocketConnection.close()
      state = Closed
    }

    def sendCall(msg: SrpcCall): Future[SrpcResponse] = synchronized {
      val callId = callIdGenerator.next()
      val responsePromise = Promise[SrpcResponse]()

      state match {
        case Open =>
          callIdCache.put(callId, responsePromise)
          Try {
                webSocketConnection.send(TransportMessageParser.writeJValue(SrpcEnvelope(callId, msg)))
          } match {
            case Success(()) => responsePromise.future
            case Failure(e)  => Future.failed(e)
          }
        case Closing | Closed =>
          Future.failed(new IllegalStateException("Connection already closed"))
      }
    }

    private[DefaultSrpcComponent] def handleIncomingCall(req: SrpcCall): Future[SrpcResponse] = synchronized {
      state match {
        case Open =>
          numIncomingCalls += 1
          onSrpcCall(req)
        case Closing | Closed =>
          Future.successful(SrpcCallError(PayloadErrorCode.GenericError, "Connection is closing"))
      }
    }

    private[DefaultSrpcComponent] def handleIncomingResponse(callId: String, res: SrpcResponse): Unit = synchronized {
      state match {
        case Closed =>
          logger.warn("Received response with call ID {} while already closed. Dropping.", callId)
        case Open | Closing =>
          val cachedResponsePromise = callIdCache.remove(callId)

          cachedResponsePromise match {
            case None =>
              logger.warn("Received response to no call, the unknown call ID is {}", callId)
            case Some(resPromise) =>
              resPromise.success(res)
              ()
          }
      }
    }

    private[DefaultSrpcComponent] def handleOutgoingResponse(callId: String, msg: SrpcResponse): Unit = synchronized {
      try {
        state match {
          case Closed =>
            logger.warn(s"WebSocket connection closed before we could respond to call; call ID $callId")
          case Open | Closing =>
            val resEnvelope = SrpcEnvelope(callId, msg)
            webSocketConnection.send(TransportMessageParser.writeJValue(resEnvelope))
        }
      } finally {
        srpcConnection.incomingCallEnded()
      }
    }

    private[DefaultSrpcComponent] def handleWebSocketDisconnect(): Unit = synchronized {
      state = Closed
    }

    /**
      * Should be called whenever SrpcConnection has received the response to an
      * incoming call from the call handler, or definitively failed to do
      * so.
      *
      * @return Whether the WebSocket connection should now be closed
      */
    private def incomingCallEnded(): Unit = {
      numIncomingCalls -= 1

      if (numIncomingCalls == 0) {
        if (state == Closing)
          executeGracefulClose()
        else if (state == Closed)
          completeGracefulCloseFuture()
      }
    }

    private def executeGracefulClose(): Unit = {
      webSocketConnection.close()
      state = Closed
      completeGracefulCloseFuture()
    }

    private def completeGracefulCloseFuture(): Unit =
      closePromise.foreach(_.success(()))
  }

  def srpcConnection: DefaultSrpcConnection

  def onMessage(jval: JValue): Unit = {
    val reqEnvelope = TransportMessageParser.parse(jval)
    logger.debug(s"onMessage called on $reqEnvelope")
    reqEnvelope.payload match {
      case req: SrpcCall =>
        logger.debug("Passing call to onSrpcCall")
        srpcConnection.handleIncomingCall(req) recover {
          case NonFatal(e) => SrpcCallError(
            PayloadErrorCode.InternalError,
            "error getting result in SRPC layer"
          )
        } foreach { msg: SrpcResponse =>
          srpcConnection.handleOutgoingResponse(reqEnvelope.callId, msg)
        }
      case response: SrpcResponse =>
        srpcConnection.handleIncomingResponse(reqEnvelope.callId, response)
    }
  }

  def onWebSocketDisconnect(): Unit = {
    srpcConnection.handleWebSocketDisconnect()
    onSrpcDisconnect()
  }

  // TODO don't throw these away!
  def onError(ex: Throwable): Unit =
    logger.error("WebSocket error", ex)
}

object DefaultSrpcComponent {

  /**
    * Our SRPC connections can be in three states:
    *
    * * OPEN (calls and responses can be sent)
    * * CLOSING (only responses can be sent, trying to send a call results in
    * an error)
    * * CLOSED (nothing can be sent and the underlying WebSocket is closed too)
    *
    * This is done so that when an application calls closes an OCPP connection, we
    * give asynchronous call processors a chance to respond before shutting
    * down the WebSocket connection which would lead to unexpected errors.
    *
    * Unfortunately OCPP has no mechanism to tell the remote side we're about to
    * close, so they might still send new calls while we're CLOSING. In that
    * case the SRPC layer will send them a CALLERROR with a GenericError error
    * code.
    *
    * Note that this state is about the SRPC level in the network stack. The
    * WebSocket connection should be fully open when the SRPC layer is in Open
    * or Closing state. When the WebSocket is closed remotely, we go immediately
    * from Open to Closed.
    */
  sealed trait ConnectionState
  case object Open    extends ConnectionState
  case object Closing extends ConnectionState
  case object Closed  extends ConnectionState
}
