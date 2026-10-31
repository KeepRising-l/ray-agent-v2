package cn.ray.agent.server.chat.route;

import cn.ray.agent.runtime.agent.RunBudget;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.chat.ChatWorkflow;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowConcurrencyTest {
    @Test void fourRootRequestsCanAllAwaitAutoChildrenWithoutStarvingTheirWorkers() throws Exception {
        var runner=new WorkflowRunner();
        try {
            var arrived=new CountDownLatch(4);
            var input=new GenerateRequest(List.of(new Message(Message.Role.USER,"你好")));
            var budget=new RunBudget(5,2,3000,500,24000);
            var events=new ChatWorkflow.Events(event->{},event->{});
            LlmAdapter model=request->subscriber->subscriber.onSubscribe(new Flow.Subscription(){
                boolean sent;
                public void request(long n){if(sent)return;sent=true;subscriber.onNext(new StreamChunk.TextDelta("你好"));subscriber.onNext(new StreamChunk.Finished(StreamChunk.FinishReason.STOP,null));subscriber.onComplete();}
                public void cancel(){}
            });
            var root=runner.bind(model,budget,events,request->execution->{
                arrived.countDown();assertTrue(arrived.await(1,TimeUnit.SECONDS));
                var child=runner.bind(execution.model,budget,events,ignored->nested->nested.collect(nested.model,input,true));
                execution.collect(child,input,true);
            },"RUN");
            var probes=new ArrayList<AutoWorkflowTest.Probe>();
            for(int i=0;i<4;i++){var probe=new AutoWorkflowTest.Probe();probes.add(probe);root.generate(input).subscribe(probe);}
            for(var probe:probes){probe.done.get(4,TimeUnit.SECONDS);assertNull(probe.error);assertEquals("你好",probe.text.toString());}
        } finally {runner.close();}
    }
}
