package reactor.ipc.aeron;

import io.aeron.Publication;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * @author Anatoly Kadyshev
 */
public class AeronWriteSequencerBenchmark {

    private final String channel;

    private final int nRuns;

    public static void main(String[] args) {
        new AeronWriteSequencerBenchmark("aeron:ipc?endpoint=benchmark", 10).run();
    }

    AeronWriteSequencerBenchmark(String channel, int nRuns) {
        this.channel = channel;
        this.nRuns = nRuns;
    }

    public void run() {
        AeronOptions options = new AeronOptions();
        AeronWrapper aeron = new AeronWrapper("bench", options);
        io.aeron.Subscription subscription = aeron.addSubscription(channel, 1, "benchmark", 0);

        BenchmarkPooler pooler = new BenchmarkPooler(subscription);
        pooler.schedulePoll();

        Publication publication = aeron.addPublication(channel, 1, "benchmark", 0);
        MessagePublication messagePublication = new DefaultMessagePublication(publication, "benchmark",
                options.connectTimeoutMillis(), options.backpressureTimeoutMillis());
        Scheduler scheduler = Schedulers.single();
        AeronWriteSequencer sequencer = new AeronWriteSequencer(scheduler, "test", messagePublication, 1);

        for (int i = 1; i <= nRuns; i++) {
            Publisher<ByteBuffer> publisher = new BenchmarkPublisher(1_000_000, 512);

            long start = System.nanoTime();
            Mono<Void> result = sequencer.add(publisher);
            result.block();
            long end = System.nanoTime();

            System.out.printf("Run %d of %d - completed, took: %d millis\n", i, nRuns, Duration.ofNanos(end - start).toMillis());
        }

        pooler.dispose();
        aeron.dispose();
    }

    private static class BenchmarkPooler implements Disposable {

        private final io.aeron.Subscription subscription;

        private final Scheduler scheduler;

        public BenchmarkPooler(io.aeron.Subscription subscription) {
            this.subscription = subscription;
            this.scheduler = Schedulers.newSingle("drainer");
        }

        void schedulePoll() {
            scheduler.schedule(() -> {
                BackoffIdleStrategy idleStrategy = AeronUtils.newBackoffIdleStrategy();

                for ( ; ; ) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    int nPolled = subscription.poll((buffer, offset, length, header) -> {}, 1000);

                    idleStrategy.idle(nPolled);
                }
            });
        }

        @Override
        public void dispose() {
            scheduler.dispose();
        }
    }

    private static class BenchmarkPublisher implements Publisher<ByteBuffer> {

        final int nSignals;

        final int bufferSize;

        private BenchmarkPublisher(int nSignals, int bufferSize) {
            this.nSignals = nSignals;
            this.bufferSize = bufferSize;
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> s) {
            s.onSubscribe(new Subscription() {

                ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

                long nPublished = 0;

                volatile boolean cancelled = false;

                long requested = 0;

                boolean publishing = false;

                @Override
                public void request(long n) {
                    requested += n;

                    if (publishing) {
                        return;
                    }

                    publishing = true;
                    while (nPublished < nSignals && requested > 0) {
                        if (cancelled) {
                            break;
                        }

                        s.onNext(buffer);

                        nPublished += 1;
                        requested--;

                        if (nPublished % (nSignals / 10) == 0) {
                            DebugUtil.log("Signals published: " + nPublished);
                        }

                        if (nPublished == nSignals) {
                            s.onComplete();
                            break;
                        }
                    }
                    publishing = false;
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }

    }
}
