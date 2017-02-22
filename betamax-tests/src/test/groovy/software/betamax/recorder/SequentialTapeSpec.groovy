/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.betamax.recorder

import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.RequestBody
import software.betamax.junit.Betamax
import software.betamax.message.tape.RecordedRequest
import software.betamax.message.tape.RecordedResponse
import software.betamax.tape.yaml.YamlTapeLoader
import spock.lang.Issue
import spock.lang.Shared
import spock.lang.Specification

import static com.google.common.net.MediaType.JSON_UTF_8
import static java.net.HttpURLConnection.*
import static software.betamax.TapeMode.READ_SEQUENTIAL

@Issue([
    "https://github.com/robfletcher/betamax/issues/7",
    "https://github.com/robfletcher/betamax/pull/70"
])
class SequentialTapeSpec extends Specification {

  static
  final TAPE_ROOT = new File(SequentialTapeSpec.getResource("/betamax/tapes").toURI())
  @Shared def tapeLoader = new YamlTapeLoader(TAPE_ROOT)

  void "read sequential tapes play back recordings in correct sequence"() {
    given: "a tape in read-sequential mode"
    def tape = tapeLoader.loadTape("sequential tape")
    tape.mode = READ_SEQUENTIAL

    when: "the tape is read multiple times"
    List<RecordedResponse> responses = []
    n.times {
      responses << tape.play(request)
    }

    then: "each read succeeds"
    responses.every {
      it.status == HTTP_OK
    }

    and: "each has different content"
    responses.bodyAsText == (1..n).collect {
      "count: $it"
    }

    where:
    n = 2
    request = new RecordedRequest.Builder()
        .url("http://freeside.co/betamax")
        .build()
  }

  @Betamax(tape = "sequential tape", mode = READ_SEQUENTIAL)
  void "read sequential tapes return an error if more than the expected number of requests are made"() {
    given: "a tape in read-sequential mode"
    def tape = tapeLoader.loadTape("sequential tape")
    tape.mode = READ_SEQUENTIAL

    and: "all recorded requests have already been played"
    n.times {
      tape.play(request)
    }

    when: "the tape is read again"
    tape.play(request)

    then: "an exception is thrown"
    thrown IndexOutOfBoundsException

    where:
    n = 2
    request = new RecordedRequest.Builder()
        .url("http://freeside.co/betamax")
        .build()
  }

  void "can read sequential responses from tapes with other content"() {
    given: "a tape in read-sequential mode"
    def tape = tapeLoader.loadTape("rest conversation tape")
    tape.mode = READ_SEQUENTIAL

    and: "several sequential requests"
    def getRequest = new RecordedRequest.Builder()
        .url(url)
        .build()
    def postRequest = new RecordedRequest.Builder()
        .method("POST", RequestBody.create(MediaType.parse(JSON_UTF_8.toString()), '{"name":"foo"}'))
        .url(url)
        .build()

    when: "the requests are played back in sequence"
    List<RecordedResponse> responses = []
    responses << tape.play(getRequest)
    responses << tape.play(postRequest)
    responses << tape.play(getRequest)

    then: "all play back successfully"
    responses.status == [HTTP_NOT_FOUND, HTTP_CREATED, HTTP_OK]

    and: "the correct data is played back"
    new JsonSlurper().parseText(responses[2].bodyAsText).name == "foo"

    where:
    url = "http://freeside.co/thing/1"
  }

  @Betamax(tape = "rest conversation tape", mode = READ_SEQUENTIAL)
  void "out of sequence requests cause an error"() {
    given: "a tape in read-sequential mode"
    def tape = tapeLoader.loadTape("rest conversation tape")
    tape.mode = READ_SEQUENTIAL

    and: "the first interaction is played back"
    tape.play(request)

    when: "another request is made out of the expected sequence"
    tape.play(request)

    then: "an exception is thrown"
    thrown IllegalStateException

    where:
    request = new RecordedRequest.Builder()
        .url("http://freeside.co/thing/1")
        .build()
  }
}
