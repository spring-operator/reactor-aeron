package reactor.ipc.aeron;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class AeronWriteSequencer extends WriteSequencer<ByteBuffer> {

    private static final Logger logger = Loggers.getLogger(AeronWriteSequencer.class);

    private final long sessionId;

    private final InnerSubscriber<ByteBuffer> inner;

    private final Consumer<Throwable> errorHandler;

    AeronWriteSequencer(Scheduler scheduler,
                        String category,
                        MessagePublication publication,
                        long sessionId,
                        long backPressureTimeoutNs) {
        super(scheduler, discardedPublisher -> {});
        this.sessionId = sessionId;
        this.errorHandler = th -> logger.error("[{}] Unexpected exception", category, th);
        this.inner = new SignalSender(this, publication, this.sessionId, backPressureTimeoutNs);
    }

    @Override
    Consumer<Throwable> getErrorHandler() {
        return errorHandler;
    }

    @Override
    InnerSubscriber<ByteBuffer> getInner() {
        return inner;
    }

    static class SignalSender extends InnerSubscriber<ByteBuffer> {

        private final long sessionId;

        private final MessagePublication publication;

        private final Queue<ByteBuffer> queue;

        private long retryStartTime;

        private volatile boolean backpressured = false;

        private long backpressureTimeoutNs;

        private final ByteBuffer COMPLETE = ByteBuffer.allocate(0);

        private final ByteBuffer ERROR = ByteBuffer.allocate(0);

        private volatile Throwable lastError;

        SignalSender(AeronWriteSequencer sequencer, MessagePublication publication, long sessionId, long backpressureTimeoutNs) {
            super(sequencer, 16);

            this.sessionId = sessionId;
            this.publication = publication;
            this.backpressureTimeoutNs = backpressureTimeoutNs;
            this.queue = Queues.<ByteBuffer>get(16).get();
        }

        @Override
        void doOnSubscribe() {
            request(Long.MAX_VALUE);
        }

        @Override
        boolean doOnNext(ByteBuffer byteBuffer) {
            if (!backpressured) {
                Exception cause = null;
                long result = 0;
                try {
                    result = publication.publish(MessageType.NEXT, byteBuffer, sessionId);
                    if (result > 0) {
                        return true;
                    } else {
                        retryStartTime = System.nanoTime();
                        backpressured = true;
                        queue.offer(byteBuffer);

                        scheduleRetry();

                        return false;
                    }
                } catch (Exception ex) {
                    cause = ex;
                }

                cancel();

                promise.error(new Exception("Failed to publish signal into session with Id: " + sessionId
                        + ", result: " + result, cause));

                scheduleNextPublisherDrain();

                return false;
            } else {
                queue.offer(byteBuffer);
                return false;
            }
        }

        private void scheduleRetry() {
            Schedulers.single().schedule(() -> {

                retryPublication();

            }, 100, TimeUnit.MILLISECONDS);
        }

        void retryPublication() {
            do {
                ByteBuffer buffer = queue.peek();

                if (buffer == COMPLETE) {
                    backpressured = false;
                    promise.success();
                    scheduleNextPublisherDrain();
                    return;
                }

                if (buffer == ERROR) {
                    promise.error(lastError);
                    lastError = null;

                    scheduleNextPublisherDrain();
                }

                long result = publication.publish(MessageType.NEXT, buffer, sessionId);
                if (result > 0) {
                    queue.poll();
                } else {
                    if (System.nanoTime() - retryStartTime < backpressureTimeoutNs) {
                        scheduleRetry();
                    } else {
                        backpressured = false;
                        queue.clear();

                        cancel();

                        promise.error(new Exception("Failed to publish signal into session with Id: " + sessionId
                                + ", result: " + result + " due to backpressured publication"));

                        scheduleNextPublisherDrain();
                    }
                    return;
                }
            } while(!queue.isEmpty());

            backpressured = false;

            requestFromUpstream(actual);
        }

        @Override
        void doOnError(Throwable t) {
            if (!backpressured) {
                promise.error(t);

                scheduleNextPublisherDrain();
            } else {
                lastError = t;
                queue.offer(ERROR);
            }
        }

        @Override
        void doOnComplete() {
            if (!backpressured) {
                promise.success();

                scheduleNextPublisherDrain();
            } else {
                queue.offer(COMPLETE);
            }
        }

    }

}
