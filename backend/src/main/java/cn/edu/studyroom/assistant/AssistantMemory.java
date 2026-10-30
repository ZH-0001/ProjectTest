package cn.edu.studyroom.assistant;

import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.messages.Message;

/** Stored on the HttpSession, so logout and expiry close memory without a second identity registry. */
public final class AssistantMemory implements HttpSessionBindingListener {
    public final long user;
    public volatile long version;
    public volatile boolean alive=true;
    public SearchCriteria criteria=SearchCriteria.empty();
    public Set<String> knownRooms=new LinkedHashSet<>();
    public final Deque<List<Message>> turns=new ArrayDeque<>();
    public final Deque<Map<String,Object>> displayTurns=new ArrayDeque<>();
    public final LinkedHashMap<String,AssistantCard> cards=new LinkedHashMap<>();
    public final LinkedHashMap<String,Pending> results=new LinkedHashMap<>();
    public Pending running;
    public AssistantMemory(long user) { this.user=user; }
    public static final class Pending {
        public final String message;
        public final long version;
        public final CompletableFuture<Map<String,Object>> future=new CompletableFuture<>();
        public final TurnBudget budget;
        public volatile boolean cancelled;
        public volatile Thread thread;
        public Instant finished;
        public Pending(String message,long version,Duration duration,AssistantMemory memory) {
            this.message=message;this.version=version;
            budget=new TurnBudget(duration,()->memory.alive && memory.version==version && !cancelled);
        }
        public void cancel() { cancelled=true;var t=thread;if(t!=null)t.interrupt(); }
    }
    public synchronized void reset() {
        version++;if(running!=null)running.cancel();
        turns.clear();displayTurns.clear();cards.clear();results.clear();knownRooms.clear();criteria=SearchCriteria.empty();
    }
    public synchronized void clean(Instant now) {
        cards.entrySet().removeIf(e->!e.getValue().generatedAt.plusSeconds(1800).isAfter(now));
        while(cards.size()>100)cards.remove(cards.keySet().iterator().next());
        results.entrySet().removeIf(e->e.getValue()!=running && e.getValue().finished!=null && !e.getValue().finished.plusSeconds(1800).isAfter(now));
        while(results.size()>200) {
            var old=results.entrySet().stream().filter(e->e.getValue()!=running).findFirst();
            if(old.isEmpty())break;results.remove(old.get().getKey());
        }
    }
    @Override public synchronized void valueUnbound(HttpSessionBindingEvent event) { alive=false;reset(); }
}
