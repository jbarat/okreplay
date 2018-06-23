package okreplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Stack;
import java.util.logging.Logger;

import static java.util.Collections.unmodifiableList;
import static okreplay.Util.VIA;

/**
 * Represents a set of recorded HTTP stackedInteractions that can be played back or
 * appended to.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
abstract class MemoryTape implements Tape {

  private static final Logger LOG = Logger.getLogger(MemoryTape.class.getName());

  private String name;
  private List<YamlRecordedInteraction> interactions = new ArrayList<>();
  private LinkedHashMap<Request, Stack<YamlRecordedInteraction>> stackedInteractions = new LinkedHashMap<>();
  private transient TapeMode mode = OkReplayConfig.DEFAULT_MODE;
  private transient MatchRule matchRule = OkReplayConfig.DEFAULT_MATCH_RULE;


    @Override public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @Override public TapeMode getMode() {
    return mode;
  }

  @Override public void setMode(TapeMode mode) {
    this.mode = mode;
  }

  @Override public MatchRule getMatchRule() {
    return this.matchRule;
  }

  @Override public void setMatchRule(MatchRule matchRule) {
    this.matchRule = matchRule;
  }

  @Override public boolean isReadable() {
    return mode.isReadable();
  }

  @Override public boolean isWritable() {
    return mode.isWritable();
  }

  @Override public boolean isSequential() {
    return mode.isSequential();
  }

  @Override public int size() {
      return interactions.size();
  }

  public List<YamlRecordedInteraction> getInteractions() {
      return unmodifiableList(interactions);
  }

  public synchronized void setInteractions(List<YamlRecordedInteraction> interactions) {
      for (YamlRecordedInteraction interaction : interactions) {
          int position = findMatch(interaction.toImmutable().request());

          if (position >= 0) {
              Request key = getRequestForPosition(position);
              if (mode.isSequential()) {
                  Stack<YamlRecordedInteraction> stack = this.stackedInteractions.get(key);
                  stack.push(interaction);
              } else {
                  Stack<YamlRecordedInteraction> stack = new Stack<>();
                  stack.push(interaction);
                  this.stackedInteractions.put(key, stack);
              }
          } else {
              Stack<YamlRecordedInteraction> stack = new Stack<>();
              stack.push(interaction);
              this.stackedInteractions.put(interaction.toImmutable().request(), stack);
          }
      }
  }

  @Override public synchronized boolean seek(Request request) {
      if(stackedInteractions.isEmpty()){
      processInteractions();
    }

    if (isSequential()) {
      try {
        Integer index = findMatch2(request);
        return !stackedInteractions.get(getRequestForPosition(index)).empty();
      } catch (IndexOutOfBoundsException e) {
        throw new IndexOutOfBoundsException("No matching found for request: " + request.url());
      }
    } else {
      return findMatch2(request) >= 0;
    }
  }

  private synchronized void processInteractions() {
    for (YamlRecordedInteraction interaction : interactions) {
      int position = findMatch2(interaction.toImmutable().request());

      if(position >= 0){
        Request key = getRequestForPosition(position);
        if(mode.isSequential()){
          Stack<YamlRecordedInteraction> stack = stackedInteractions.get(key);
          stack.push(interaction);
        }else{
          Stack<YamlRecordedInteraction> stack = new Stack<>();
          stack.push(interaction);
          stackedInteractions.put(key, stack);
        }
      }else{
        Stack<YamlRecordedInteraction> stack = new Stack<>();
        stack.push(interaction);
        stackedInteractions.put(interaction.toImmutable().request(), stack);
      }
    }

    for (Request request : stackedInteractions.keySet()) {
      Collections.reverse(stackedInteractions.get(request));
    }
  }

  @Override public synchronized Response play(final Request request) {
    if(stackedInteractions.isEmpty()){
      processInteractions();
    }

    if (!mode.isReadable()) {
      throw new IllegalStateException("the tape is not readable");
    }

    int position = findMatch2(request);
      if (position < 0) {
          throw new IllegalStateException("no matching recording found");
      } else {
          if (mode.isSequential()) {
              Stack<YamlRecordedInteraction> recordedInteractionStack =
                      stackedInteractions.get(getRequestForPosition(position));
              YamlRecordedInteraction recordedInteraction;
              int stackSize = recordedInteractionStack.size();
              if (stackSize > 1) {
                  throw new IllegalStateException("popping");
//                  LOG.info("Stack size: " + stackSize+ ",popping");
//                  recordedInteraction = recordedInteractionStack.pop();
              } else {
                  throw new IllegalStateException("peeking");
//                  LOG.info("Stack size: " + stackSize+ ",peeking");
//                  recordedInteraction = recordedInteractionStack.peek();
              }
//              return recordedInteraction.toImmutable().response();
          } else {
              return stackedInteractions.get(getRequestForPosition(position)).peek().toImmutable().response();
          }
      }
  }

  private String stringify(Request request) {
    byte[] body = request.body() != null ? request.body() : new byte[0];
    String bodyLog = " (binary " + body.length + "-byte body omitted)";
    return "method: " + request.method() + ", " + "uri: " + request.url() + ", " + "headers: " +
        request.headers() + ", " + bodyLog;
  }

  @Override public synchronized void record(Request request, Response response) {
    if (!mode.isWritable()) {
      throw new IllegalStateException("the tape is not writable");
    }

    RecordedInteraction interaction = new RecordedInteraction(new Date(), recordRequest(request),
        recordResponse(response));

    if (mode.isSequential()) {
      interactions.add(interaction.toYaml());
    } else {
      int position = findMatch(request);
      if (position >= 0) {
        interactions.set(position, interaction.toYaml());
      } else {
        interactions.add(interaction.toYaml());
      }
    }
  }

  @Override public String toString() {
    return String.format("Tape[%s]", name);
  }

  private synchronized int findMatch(final Request request) {
    return Util.indexOf(interactions.iterator(), new Predicate<YamlRecordedInteraction>() {
      @Override public boolean apply(YamlRecordedInteraction input) {
        return matchRule.isMatch(request, input.toImmutable().request());
      }
    });
  }

  private synchronized int findMatch2(final Request request) {
    return Util.indexOf(stackedInteractions.keySet().iterator(), new Predicate<Request>() {
      @Override public boolean apply(Request input) {
        return matchRule.isMatch(request, input);
      }
    });
  }

  private Request recordRequest(Request request) {
    return request.newBuilder()
        .removeHeader(VIA)
        .build();
  }

  private Response recordResponse(Response response) {
    return response.newBuilder()
        .removeHeader(VIA)
        .removeHeader(Headers.X_OKREPLAY)
        .build();
  }

  private Request getRequestForPosition(int position) {
        return (Request) stackedInteractions.keySet().toArray()[position];
  }
}
