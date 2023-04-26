package com.joshir.reactive.server.v1;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import pl.touk.throwing.ThrowingBiConsumer;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static pl.touk.throwing.ThrowingConsumer.unchecked;
import static pl.touk.throwing.ThrowingBiConsumer.unchecked;


public class DefaultConnection extends BaseSubscriber<ByteBuffer> implements Connection {
  final SocketChannel socketChannel;
  final Flux<SelectionKey> readNotifier;
  final Flux<SelectionKey> writeNotifier;
  final Scheduler scheduler;
  volatile SelectionKey currentSelectionKey;
  ByteBuffer current;

  DefaultConnection(
          SocketChannel socketChannel,
          SelectionKey initialSelectionKey,
          Flux<SelectionKey> readNotifier,
          Flux<SelectionKey> writeNotifier
  ) {
    this.socketChannel = socketChannel;
    this.readNotifier = readNotifier;
    this.writeNotifier = writeNotifier;
    this.currentSelectionKey = initialSelectionKey;
    this.scheduler = Schedulers.single(Schedulers.parallel());
  }

  @Override
  public void close() {
    var key = currentSelectionKey;
    if (key != null) {
      dispose();
      currentSelectionKey = null;
      key.cancel();
    }
  }
  @Override
  public Flux<ByteBuffer> receive() {
    return readNotifier
      .doOnSubscribe(unchecked(__ -> {
        var selector = currentSelectionKey.selector();
        socketChannel.register(selector, SelectionKey.OP_READ);
        selector.wakeup();
      }))
      .onBackpressureLatest()
      .doOnNext(sk -> currentSelectionKey = sk)
      .publishOn(scheduler)
      .doOnCancel(this::close)
      .handle(unchecked(new ThrowingBiConsumer<SelectionKey, SynchronousSink<ByteBuffer>, IOException>() {
        @Override
        public void accept(SelectionKey sk, SynchronousSink<ByteBuffer> sink) throws IOException {
          var buffer = ByteBuffer.allocateDirect(1024);
          var read = socketChannel.read(buffer);

          if (read > 0) {
            sink.next(buffer.flip());
          }
        }
      }));
  }

  @Override
  public Mono<Void> send(Publisher<ByteBuffer> dataStream) {
    return Mono
      .<Void>fromRunnable(() ->
        Flux.from(dataStream)
          .subscribe(this)
      )
      .doOnCancel(this::close);
  }

  @Override
  protected void hookOnSubscribe(Subscription subscription) {
    subscription.request(1);
    writeNotifier
      .doOnNext(sk -> {
        currentSelectionKey = sk;
        sk.interestOps(SelectionKey.OP_READ);
      })
      .publishOn(scheduler)
      .subscribe(__ -> hookOnNext(current));
  }

  @Override
  protected void hookOnNext(ByteBuffer buffer) {
    int result;

    try {
      result = socketChannel.write(buffer);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    if (result == -1) {
      cancel();
    }

    if (buffer.hasRemaining()) {
      current = buffer;
      var key = currentSelectionKey;

      key.interestOps(SelectionKey.OP_WRITE);
      key.selector().wakeup();

      return;
    }
    request(1);
  }
}