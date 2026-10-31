package cn.ray.agent.server.run;

import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.task.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunQueueTest {
    @Test void oldConnectionCleanupCannotRemoveResumedRegistration() {
        var registry=new cn.ray.agent.server.conversation.SessionRegistry();
        var old=registry.register("owner","run");
        var resumed=registry.register("owner","run");
        registry.remove(old);
        assertTrue(registry.attached("owner","run"));
        assertFalse(registry.attached("other","run"));
        registry.remove(resumed);
        assertFalse(registry.attached("owner","run"));
    }
    private void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    @Test void fifoCapacityAndOwnerLimitCountWholeAsynchronousLifetime() throws Exception {
        var executor=new BoundedRunExecutor(1,2,2);
        try {
            var first=executor.reserve("a");var second=executor.reserve("a");var third=executor.reserve("b");
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->executor.reserve("c"));
            var order=new CopyOnWriteArrayList<Integer>();
            executor.submit(first,()->order.add(1));executor.submit(second,()->order.add(2));executor.submit(third,()->order.add(3));
            until(()->order.size()==1);assertEquals(List.of(1),order);assertEquals(2,executor.capacity().queued());
            first.close();until(()->order.size()==2);assertEquals(List.of(1,2),order);
            second.close();until(()->order.size()==3);third.close();third.close();
            assertEquals(0,executor.capacity().admitted());assertEquals(0,executor.capacity().running());assertEquals(1,executor.capacity().rejected());
        } finally {executor.close();}
    }
    @Test void queuedCancellationNeverStartsAndReservationCanBeCancelledBeforeSubmit() throws Exception {
        var executor=new BoundedRunExecutor(1,1,0);
        try {
            var active=executor.reserve("owner");var queued=executor.reserve("owner");
            var started=new AtomicBoolean();var forbidden=new AtomicBoolean();
            executor.submit(active,()->started.set(true));until(started::get);
            executor.submit(queued,()->forbidden.set(true));queued.close();active.close();
            var early=executor.reserve("owner");early.close();executor.submit(early,()->forbidden.set(true));
            assertFalse(forbidden.get());assertEquals(0,executor.capacity().admitted());
        } finally {executor.close();}
    }
    @Test void perOwnerLimitDoesNotBlockOtherOwners() {
        var executor=new BoundedRunExecutor(2,2,1);
        try {
            var a=executor.reserve("a");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->executor.reserve("a"));
            var b=executor.reserve("b");a.close();b.close();assertEquals(0,executor.capacity().admitted());
        } finally {executor.close();}
    }
    @Test void cancelledWorkerPlaceholderCannotFillExecutorQueue() throws Exception {
        var executor=new BoundedRunExecutor(1,0,0);
        var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var first=executor.reserve("owner");
            executor.submit(first,()->{
                blocked.countDown();
                try { release.await(); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(2,TimeUnit.SECONDS));first.close();
            // 首个底层线程仍阻塞；后续逻辑槽反复预留和取消，不应撑满线程池队列。
            for(int i=0;i<10;i++) {
                var cancelled=executor.reserve("owner");
                executor.submit(cancelled,()->fail("cancelled job executed"));
                cancelled.close();
            }
            var last=executor.reserve("owner");var ran=new AtomicBoolean();
            executor.submit(last,()->ran.set(true));release.countDown();
            until(ran::get);last.close();assertEquals(0,executor.capacity().admitted());
        } finally {release.countDown();executor.close();}
    }
    @Test void bridgeCarriesDemandAndCancellationAcrossQueueBoundary() throws Exception {
        var executor=new BoundedRunExecutor(1,1,0);var states=mock(TaskStateRepository.class);
        when(states.start(anyString(),anyString())).thenReturn(true);
        var dispatcher=new QueuedRunDispatcher(executor,states);
        var subscribers=new CopyOnWriteArrayList<Flow.Subscriber<? super StreamChunk>>();
        LlmAdapter slow=request->subscriber->{subscribers.add(subscriber);subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){}});};
        var request=new GenerateRequest(List.of(new Message(Message.Role.USER,"q")));
        var first=new Probe();var second=new Probe();
        try {
            var p=executor.reserve("o");var q=executor.reserve("o");
            dispatcher.dispatch(new RunDispatcher.Context("o","r1",p,()->{}),slow,request,first);until(()->subscribers.size()==1);
            dispatcher.dispatch(new RunDispatcher.Context("o","r2",q,()->{}),slow,request,second);
            second.subscription.cancel();first.subscription.cancel();
            assertEquals(1,subscribers.size());assertEquals(0,executor.capacity().admitted());
            verify(states,never()).start("o","r2");
        } finally {executor.close();}
    }
    @Test void flowCompletionRatherThanSubscriptionReturnReleasesSlot() throws Exception {
        var executor=new BoundedRunExecutor(1,0,0);var states=mock(TaskStateRepository.class);when(states.start(anyString(),anyString())).thenReturn(true);
        try {
            var dispatcher=new QueuedRunDispatcher(executor,states);var captured=new AtomicReference<Flow.Subscriber<? super StreamChunk>>();
            LlmAdapter adapter=request->subscriber->{captured.set(subscriber);subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){}});};
            var probe=new Probe();dispatcher.dispatch(new RunDispatcher.Context("o","r",executor.reserve("o"),()->{}),adapter,
                    new GenerateRequest(List.of(new Message(Message.Role.USER,"q"))),probe);
            until(()->captured.get()!=null);assertEquals(1,executor.capacity().admitted());
            captured.get().onComplete();until(()->executor.capacity().admitted()==0);assertTrue(probe.done);
        } finally {executor.close();}
    }
    static class Probe implements Flow.Subscriber<StreamChunk>{
        Flow.Subscription subscription;volatile boolean done;
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(Long.MAX_VALUE);}
        public void onNext(StreamChunk c){}
        public void onError(Throwable e){done=true;}
        public void onComplete(){done=true;}
    }
}
