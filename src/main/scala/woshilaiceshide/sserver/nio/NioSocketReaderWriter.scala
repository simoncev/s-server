package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels._
import java.nio.charset._

import java.nio.channels.SelectionKey._

import scala.annotation.tailrec

import woshilaiceshide.sserver.utility._
import woshilaiceshide.sserver.utility.Utility

import SelectorRunner._

private[nio] object NioSocketReaderWriter {

  //Byte or Int for helper given padding/alignment, object sizes are the same.
  final class BytesNode(val bytes: ByteBuffer, var next: BytesNode = null, val helper: Int)
  final class BytesList(val head: BytesNode, var last: BytesNode = null) {
    def append(x: ByteBuffer, helper: Byte) = {
      val newed = new BytesNode(x, null, helper)
      if (null == last) {
        head.next = newed
        last = newed
        this
      } else {
        last.next = newed
        last = newed
        this
      }
    }
    def append(x: BytesList) = {
      if (null == last) {
        head.next = x.head
        last = x.last
        this
      } else {
        last.next = x.head
        last = x.last
        this
      }
    }
  }

}

class NioSocketReaderWriter private[nio] (
    channel_hander_factory: ChannelHandlerFactory,
    configurator: NioConfigurator) extends SelectorRunner(configurator) {

  import NioSocketReaderWriter._
  import configurator._

  private val socket_max_idle_time_in_seconds_1 = if (0 < socket_max_idle_time_in_seconds) socket_max_idle_time_in_seconds else 60
  select_timeout = Math.min(socket_max_idle_time_in_seconds_1 * 1000, default_select_timeout)

  private var buffer_pool_used_by_io_thread = configurator.buffer_pool_factory.get_pool_used_by_io_thread()
  private var buffer_pool_used_by_biz_thread = configurator.buffer_pool_factory.get_pool_used_by_biz_thread()

  this.register_on_termination {
    buffer_pool_used_by_io_thread.free()
    buffer_pool_used_by_io_thread = null
    buffer_pool_used_by_biz_thread.free()
    buffer_pool_used_by_biz_thread = null
    configurator.buffer_pool_factory.free()
  }

  private val receive_buffer_size_1 = if (0 < receive_buffer_size) receive_buffer_size else 1 * 1024
  //this only client buffer will become read only before it's given to the handler
  //use head byte buffer here, because it may be read many times. 
  private val READ_BUFFER = ByteBuffer.allocate(receive_buffer_size_1)

  //some i/o operations related to those channels are pending
  //note that those operations will be checked in the order they are pended as soon as possible.
  private var asynchronousely_pended_io_operations: ReapableQueue[MyChannelWrapper] = new ReapableQueue()
  private var synchronousely_pended_io_operations: List[MyChannelWrapper] = Nil
  private def pend_for_io_operation(channelWrapper: MyChannelWrapper, in_io_worker_thread: Boolean) = {
    if (in_io_worker_thread) {
      synchronousely_pended_io_operations = channelWrapper :: synchronousely_pended_io_operations
    } else {
      asynchronousely_pended_io_operations.add(channelWrapper)
    }
  }

  this.register_on_termination {
    synchronousely_pended_io_operations = null
    asynchronousely_pended_io_operations.end()
    asynchronousely_pended_io_operations = null
  }

  private var waiting_for_register = List[SocketChannel]()
  def register_socket_channel(target: SocketChannel): Boolean = post_to_io_thread {
    waiting_for_register = target :: waiting_for_register
  }

  protected def do_start(): Unit = {}
  protected def stop_roughly(): Unit = {

    waiting_for_register.map { safe_close(_) }
    waiting_for_register = Nil

    this.iterate_registered_keys { key =>
      val attach = key.attachment()
      attach match {
        case c: NioSocketReaderWriter#MyChannelWrapper => c.close_directly()
        case _ =>
      }
    }

  }
  protected def stop_gracefully(): Boolean = {

    waiting_for_register.map { safe_close(_) }
    waiting_for_register = Nil

    if (this.get_registered_size() == 0) {
      true
    } else {
      this.iterate_registered_keys { key =>
        val attach = key.attachment()
        attach match {
          case c: NioSocketReaderWriter#MyChannelWrapper => {
            c.close(false, ChannelClosedCause.SERVER_STOPPING)
            safe_op { c.just_op_write_if_needed_or_no_op() }
          }
          case _ =>
        }
      }
      false
    }

  }
  protected def has_remaining_work(): Boolean = {
    this.get_registered_size() == 0
  }

  protected def add_a_new_socket_channel(channel: SocketChannel) = {

    channel_hander_factory.getHandler(new MyChannelInformation(channel)) match {
      case None => {
        safe_close(channel)
      }
      case Some(handler) => {
        val channelWrapper = new MyChannelWrapper(channel, handler)
        Utility.close_if_failed(channel) {
          val key = this.register(channel, SelectionKey.OP_READ, channelWrapper)
          channelWrapper.key = key
        }
        channelWrapper.open()
        if (!channel.isOpen()) {
          channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
        }
      }
    }

  }

  //avoid instantiations in hot codes.
  private val io_checker = new (MyChannelWrapper => Unit) {
    def apply(channelWrapper: MyChannelWrapper): Unit = {
      //'check_io' is very hot! 
      //but the codes produced by scala compiler is not good enough. 
      //for example, xxx.synchronized{...} will often results in unnecessary closures(scala/runtime/ObjectRef:create).
      //so, i write it using java.
      //and, i refactored public apis so that they can be used in java.
      //channelWrapper.check_io()
      JavaAccelerator.check_io(channelWrapper)
    }
  }

  private var last_check_for_idle_zombie: Long = System.currentTimeMillis()
  protected def before_next_loop(): Unit = {

    if (!is_stopping() && !waiting_for_register.isEmpty) {
      val tmp = waiting_for_register
      waiting_for_register = Nil
      tmp.map { channel =>
        safe_op { add_a_new_socket_channel(channel) }
      }
    }

    //check for idle channels and zombie channels
    //(sometimes a channel will be closed unexpectedly and the corresponding selector will not report it.)
    val now = System.currentTimeMillis()
    if (now - last_check_for_idle_zombie > configurator.check_idle_interval_in_seconds && !is_stopping()) {
      last_check_for_idle_zombie = now
      this.iterate_registered_keys { key =>
        key.attachment() match {
          case c: NioSocketReaderWriter#MyChannelWrapper =>
            c.check_idle(now)
            c.check_zombie(now)
        }
      }
    }

    //check for pending i/o, and just no deadlocks
    @tailrec def check_for_pending_io() {

      var has = false

      {
        if (!synchronousely_pended_io_operations.isEmpty) {
          val reaped = synchronousely_pended_io_operations
          synchronousely_pended_io_operations = Nil
          reaped.foreach(io_checker)
          has = true
        }
      }

      {
        val reaped = asynchronousely_pended_io_operations.reap(false)
        if (null != reaped) {
          ReapableQueueUtility.foreach(reaped, io_checker)
          has = true
        }
      }

      if (has) check_for_pending_io()

    }

    check_for_pending_io()
  }
  protected def process_selected_key(key: SelectionKey, ready_ops: Int): Unit = {

    val channel = key.channel().asInstanceOf[SocketChannel]
    val channelWrapper = key.attachment().asInstanceOf[MyChannelWrapper]

    if ((ready_ops & OP_READ) > 0) {
      try {
        val readCount = channel.read(READ_BUFFER)
        if (readCount > 0) {
          READ_BUFFER.flip()
          //channelWrapper.bytesReceived(READ_BUFFER.asReadOnlyBuffer())
          channelWrapper.bytesReceived(READ_BUFFER)
        } else {
          if (!key.isValid() || !channel.isOpen()) {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          } else if (configurator.allow_hafl_closure) {
            //-1 can not be a hint for "closed by peer" or "just input is shutdown by peer, but output is alive".
            //I tried much, but did not catch it!
            //business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation. 
            channelWrapper.clear_op_read()
            channelWrapper.inputEnded()
          } else {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_NORMALLY)
          }
        }

      } catch {
        case ex: Throwable => {
          //SelectorRunner.warn(ex, "when key is readable.")
          channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
        }
      } finally {
        READ_BUFFER.clear()
      }
    }

    if ((ready_ops & OP_WRITE) > 0) {
      try {
        channelWrapper.writing()
        if (!key.isValid() || !channel.isOpen()) {
          channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
        }
      } catch {
        case ex: Throwable => {
          SelectorRunner.warn(ex, "when key is writable.")
          channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
        }
      }
    }
  }

  protected[nio] final class MyChannelWrapper(private[nio] val channel: SocketChannel, private[nio] var handler: ChannelHandler) extends ChannelWrapper with SelectorRunner.HasKey {

    private[nio] def isInputShutdown() = channel.socket().isInputShutdown()
    private[nio] def isOutputShutdown() = channel.socket().isOutputShutdown()

    private var last_active_time = System.currentTimeMillis()

    private[nio] var status = CHANNEL_NORMAL

    private[nio] var key: SelectionKey = null
    def set_key(new_key: SelectionKey): Unit = this.key = new_key

    def remoteAddress: java.net.SocketAddress = channel.getRemoteAddress
    def localAddress: java.net.SocketAddress = channel.getLocalAddress

    private[nio] def close_directly() {
      val should_close = this.synchronized {
        val tmp = status
        status = CHANNEL_CLOSED
        tmp != CHANNEL_CLOSED
      }
      if (should_close) {
        safe_close(this.channel)
        safe_op(key.cancel())
        if (null != handler) safe_op {
          handler.channelClosed(this, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED, None)
          handler = null
        }
      }

    }

    private[nio] def open() {
      //handler should be not null at this time.
      /*if (null != handler)*/ handler.channelOpened(this)
    }

    //I'm intended for usage by business codes if needed.
    def closeChannel(rightNow: Boolean = false, attachment: Option[_] = None): Unit = {
      close(rightNow, ChannelClosedCause.BY_BIZ, attachment)
    }

    private[nio] var closed_cause = ChannelClosedCause.UNKNOWN
    private[nio] var attachment_for_closed: Option[_] = None
    private[NioSocketReaderWriter] def close(rightNow: Boolean = false, cause: ChannelClosedCause.Value, attachment: Option[_] = None): Unit = {
      val should_pend = this.synchronized {
        val rightNow1 = if (rightNow) true else writes == null
        if (CHANNEL_CLOSING_RIGHT_NOW != status) {
          closed_cause = cause
          attachment_for_closed = attachment
        }
        if (CHANNEL_NORMAL == status) {
          status = if (rightNow1) CHANNEL_CLOSING_RIGHT_NOW else CHANNEL_CLOSING_GRACEFULLY
          !already_pended
        } else {
          false
        }

      }

      val in_io_worker_thread = NioSocketReaderWriter.this.is_in_io_worker_thread()
      if (should_pend) {
        already_pended = true
        pend_for_io_operation(this, in_io_worker_thread)
        //if in workerThread, no need for wakeup
        if (!in_io_worker_thread) NioSocketReaderWriter.this.wakeup_selector()
      }

    }

    //Only the writing to the channel is taken into account when calculating the idle-time-out by default.
    //So if transferring big files, such as in http chunking requests that last long time, use resetIdle(). 
    def resetIdle(): Unit = this.synchronized {
      this.last_active_time = System.currentTimeMillis()
    }

    def post_to_io_thread(task: Runnable): Boolean = NioSocketReaderWriter.this.post_to_io_thread(task)

    private[nio] def inputEnded() = {
      if (null != handler) {
        handler.inputEnded(this)
      }
    }

    private[nio] def bytesReceived(bytes: ByteBuffer) = {
      //DO NOT take the received event into account for idle probing!
      //If ... then use resetIdel()
      /*this.synchronized {
        this.last_active_time = System.currentTimeMillis()
      }*/
      //perfectly
      if (null != handler) {
        //just for test
        /*while (bytes.hasRemaining()) {
          val b = ByteBuffer.wrap(Array(bytes.get()))
          val newHandler = handler.bytesReceived(b, this)
          //nothing to do with oldHandler
          this.handler = newHandler
        }*/
        val newHandler = handler.bytesReceived(bytes, this)
        //nothing to do with oldHandler
        this.handler = newHandler
      } else {
        //ignored or shutdown the tcp's upstream channel?
      }

    }

    private[nio] var writes: BytesList = null
    private var bytes_waiting_for_written = 0

    //use a (byte)flag to store the following two fields?
    private[nio] var already_pended = false
    private[nio] var should_generate_written_event = false

    @tailrec private final def write_immediately(buffer: ByteBuffer, times: Int): Unit = {
      if (0 < times) {
        /*
                   * from java doc: 
                   * ...
                   * This method may be invoked at any time. 
                   * If another thread has already initiated a write operation upon this channel, 
                   * however, then an invocation of this method will block until the first operation is complete.
                   * ...
                   */
        channel.write(buffer)
        if (buffer.hasRemaining()) {
          write_immediately(buffer, times - 1)
        }
      }
    }

    /**
     * if generate_written_event is true, then 'bytesWritten' will be fired.
     *
     */
    def write(bytes: ByteBuffer, write_even_if_too_busy: Boolean, generate_written_event: Boolean, bytes_is_reusable: Boolean): WriteResult = {

      val in_io_worker_thread = NioSocketReaderWriter.this.is_in_io_worker_thread()

      var please_wakeup = false

      val try_write = new JavaAccelerator.TryWrite()
      def set_result(result: WriteResult) = try_write.result = result
      def set_pend(pend: Boolean) = if (!try_write.pend && pend) { try_write.pend = true }

      this.synchronized {
        if (CHANNEL_NORMAL == status) {

          if (should_generate_written_event == false && generate_written_event == true) {
            should_generate_written_event = generate_written_event
            set_pend(!already_pended)
          }

          this.last_active_time = System.currentTimeMillis()

          val remaining = if (null != bytes) bytes.remaining() else 0

          if (0 == remaining) {
            set_result(WriteResult.WR_OK)

          } else if (!write_even_if_too_busy && bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
            set_result(WriteResult.WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED)

          } else {

            @tailrec def pend_bytes(buffer: ByteBuffer, pool: ByteBufferPool, used_by_io_thread: Boolean): Unit = {
              if (buffer.hasRemaining()) {

                val borrowed = pool.borrow_buffer(512)
                if (0 >= borrowed.helper) {
                  throw new Error("supposed to be not here!")
                }

                if (buffer.remaining() > borrowed.buffer.capacity()) {
                  val limit = buffer.limit()
                  buffer.limit(buffer.position() + borrowed.buffer.capacity())
                  borrowed.buffer.put(buffer)
                  borrowed.buffer.flip()
                  buffer.limit(limit)

                } else {
                  borrowed.buffer.put(buffer)
                  borrowed.buffer.flip()
                }

                val helper = if (used_by_io_thread) -borrowed.helper else borrowed.helper

                if (writes == null) {
                  writes = new BytesList(new BytesNode(borrowed.buffer, null, borrowed.helper), null)
                } else {
                  writes.append(borrowed.buffer, borrowed.helper)
                }
                if (buffer.hasRemaining()) {
                  pend_bytes(buffer, pool, used_by_io_thread)
                }
              }
            }

            def pend_reusable_bytes(buffer: ByteBuffer): Unit = {
              val helper = 0.toByte
              if (writes == null) {
                writes = new BytesList(new BytesNode(buffer, null, helper), null)
              } else {
                writes.append(buffer, helper)
              }
            }

            def write1() = {
              //move all the i/o operations into the selector's i/o thread, even if channel.write(...) is thread-safe, 
              //or the selector's i/o thread may collide with the writing thread, which will twice the cost.
              if (null == writes && in_io_worker_thread) {
                write_immediately(bytes, configurator.spin_count_when_write_immediately)
                if (bytes.hasRemaining()) {
                  bytes_waiting_for_written = bytes_waiting_for_written + bytes.remaining()
                  if (bytes_is_reusable) {
                    pend_reusable_bytes(bytes)
                  } else {
                    pend_bytes(bytes, NioSocketReaderWriter.this.buffer_pool_used_by_io_thread, true)
                  }

                }
              } else if (bytes_is_reusable) {
                bytes_waiting_for_written = bytes_waiting_for_written + remaining
                pend_reusable_bytes(bytes)

              } else if (in_io_worker_thread) {
                bytes_waiting_for_written = bytes_waiting_for_written + remaining
                pend_bytes(bytes, NioSocketReaderWriter.this.buffer_pool_used_by_io_thread, true)

              } else {
                bytes_waiting_for_written = bytes_waiting_for_written + remaining
                pend_bytes(bytes, NioSocketReaderWriter.this.buffer_pool_used_by_biz_thread, false)

              }
              set_result(WriteResult.WR_OK)
              set_pend(!already_pended && writes != null)
            }

            write1()

          }

          if (try_write.pend) {
            already_pended = true
            //if in workerThread, no need for waking up, or processor will be wasted for one more "listen()"
            please_wakeup = !in_io_worker_thread

          }

        } else {
          set_result(WriteResult.WR_FAILED_BECAUSE_CHANNEL_CLOSED)
        }
      }

      //pending comes before waking up
      if (try_write.pend) pend_for_io_operation(this, in_io_worker_thread)
      if (please_wakeup) NioSocketReaderWriter.this.wakeup_selector()

      try_write.result
    }

    private[nio] final def writing() {
      val tmp = this.synchronized {
        val x = writes
        writes = null
        x
      }
      if (null != tmp) {
        val remain = writing0(tmp, tmp.head, 0)

        val become_writable = this.synchronized {

          //clear op_write just here for optimization.
          if (null == remain._1 && writes == null) {
            try {
              this.clear_op_write()
            } catch {
              case _: Throwable => { safe_close(channel); status = CHANNEL_CLOSED; }
            }
          }

          val prev_bytes_waiting_for_written = bytes_waiting_for_written
          bytes_waiting_for_written = bytes_waiting_for_written - remain._2

          if (null == writes) {
            writes = remain._1
          } else if (null != remain._1) {
            remain._1.append(writes)
            writes = remain._1
          }

          if (prev_bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
            bytes_waiting_for_written < max_bytes_waiting_for_written_per_channel
          } else {
            false
          }
        }
        //invoked if needed only.
        if (become_writable) {
          if (handler != null) handler.channelWritable(this)
        }

      }

    }
    @tailrec private[nio] final def writing0(original_list: BytesList, node: BytesNode, written_bytes: Int): (BytesList, Int) = {

      node match {

        case null => (null, written_bytes)
        case _ => {

          val bytes = node.bytes
          val remaining = bytes.remaining()
          val written = channel.write(bytes)

          if (written == remaining) {
            if (node.helper > 0) {
              NioSocketReaderWriter.this.buffer_pool_used_by_biz_thread.return_buffer(bytes, node.helper)
            } else if (node.helper < 0) {
              NioSocketReaderWriter.this.buffer_pool_used_by_io_thread.return_buffer(bytes, (0 - node.helper).toByte)
            } else {
              //when helper is zero, the buffer is not internal, it's just a reusable buffer from the outside. 
            }
            writing0(original_list, node.next, written_bytes + written)

          } else {
            if (original_list.head eq node) {
              (original_list, written_bytes + written)
            } else {
              (new BytesList(node, original_list.last), written_bytes + written)
            }
          }
        }
      }

    }

    private[nio] def check_idle(current: Long) = {
      val (should, status1) = this.synchronized {
        if (status == CHANNEL_NORMAL &&
          current - this.last_active_time > NioSocketReaderWriter.this.socket_max_idle_time_in_seconds_1 * 1000) {
          (true, status)
        } else {
          (false, status)
        }
      }
      if (should) {
        if (null != handler) {
          handler.channelIdled(this)
        }
        this.close(true, ChannelClosedCause.BECAUSE_IDLE)
      }
      status
    }
    private[nio] def check_zombie(current: Long) = this.synchronized {
      if (status == CHANNEL_NORMAL && !this.channel.isOpen()) {
        this.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
      }
      status
    }
    private[nio] def close_if_failed(x: => Unit) = {
      try {
        x; false;
      } catch {
        case _: Throwable => { safe_close(channel); status = CHANNEL_CLOSED; true; }
      }
    }

    private[nio] def check_io(): Unit = {

      var cause: ChannelClosedCause.Value = null
      var attachment: Option[_] = None
      var generate_written_event = false

      val should_close: Boolean = this.synchronized {

        generate_written_event = this.should_generate_written_event
        should_generate_written_event = false

        cause = this.closed_cause
        attachment = this.attachment_for_closed
        already_pended = false

        if (status == CHANNEL_CLOSING_RIGHT_NOW) {
          safe_close(channel)
          @tailrec def return_writes(node: BytesNode): Unit = {
            if (node.helper > 0) {
              NioSocketReaderWriter.this.buffer_pool_used_by_biz_thread.return_buffer(node.bytes, node.helper)
            } else {
              NioSocketReaderWriter.this.buffer_pool_used_by_io_thread.return_buffer(node.bytes, node.helper)
            }
            if (null != node.next) {
              return_writes(node.next)
            }
          }
          val tmp = writes
          writes = null
          return_writes(tmp.head)
          status = CHANNEL_CLOSED
          true
        } else if (status == CHANNEL_CLOSED) {
          false
        } else if (status == CHANNEL_CLOSING_GRACEFULLY && null == writes) {
          safe_close(channel)
          status = CHANNEL_CLOSED
          true
        } else if (status == CHANNEL_CLOSING_GRACEFULLY) {
          //close_if_failed { setOpWrite() }
          close_if_failed {
            just_op_write_if_needed_or_no_op()
            //TODO tell the peer not to send data??? is it harmful to the peer if the peer can not response correctly?
            channel.shutdownInput()
          }
        } else if (status == CHANNEL_NORMAL && null == writes) {
          //close_if_failed { clearOpWrite() }
          false
        } else if (status == CHANNEL_NORMAL) {
          //TODO write immediately???
          close_if_failed { set_op_write() }
        } else {
          false
        }
      }
      //close outside, not in the "synchronization". keep locks clean.
      if (should_close) {
        safe_op {
          if (null != handler) {
            handler.channelClosed(this, cause, attachment)
            handler = null
          }
          key.cancel()
        }
      } else {
        if (generate_written_event) {
          if (null != this.handler) {
            val newHandler = this.handler.writtenHappened(this)
            //nothing to do with oldHandler
            this.handler = newHandler
          }
        }
      }
    }

    private[nio] def just_op_write_if_needed_or_no_op() = this.synchronized {
      if (key != null) {
        if ((status == CHANNEL_NORMAL || status == CHANNEL_CLOSING_GRACEFULLY) && null != writes) {
          //val key = channel.keyFor(selector)
          key.interestOps(SelectionKey.OP_WRITE)
        } else {
          //val key = channel.keyFor(selector)
          key.interestOps(0)
        }
      }

    }
    private[nio] def set_op_write() {
      if (key != null) {
        //val key = channel.keyFor(selector)
        var alreadyOps = key.interestOps()
        if ((alreadyOps & SelectionKey.OP_WRITE) == 0) {
          alreadyOps |= SelectionKey.OP_WRITE
          key.interestOps(alreadyOps)
        }
      }
    }

    private def clear_op_write() {
      if (key != null) {
        //val key = channel.keyFor(selector)
        var alreadyOps = key.interestOps()
        if ((alreadyOps & SelectionKey.OP_WRITE) != 0) {
          alreadyOps &= ~SelectionKey.OP_WRITE
          key.interestOps(alreadyOps)
        }
      }
    }

    private[nio] def clear_op_read() {
      if (key != null) {
        //val key = channel.keyFor(selector)
        var alreadyOps = key.interestOps()
        if ((alreadyOps & SelectionKey.OP_READ) != 0) {
          alreadyOps &= ~SelectionKey.OP_READ
          key.interestOps(alreadyOps)
        }
      }
    }
  }

}