/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.jepsen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import com.google.common.collect.ImmutableList;
import com.palantir.atlasdb.jepsen.events.Checker;
import com.palantir.atlasdb.jepsen.events.Event;
import com.palantir.atlasdb.jepsen.events.EventVisitor;
import com.palantir.atlasdb.jepsen.events.FailEvent;
import com.palantir.atlasdb.jepsen.events.ImmutableOkEvent;
import com.palantir.atlasdb.jepsen.events.InfoEvent;
import com.palantir.atlasdb.jepsen.events.InvokeEvent;
import com.palantir.atlasdb.jepsen.events.OkEvent;

public class NonOverlappingReadsMonotonicChecker implements Checker {
    @Override
    public CheckerResult check(List<Event> events) {
        Visitor visitor = new Visitor();
        events.forEach(event -> event.accept(visitor));
        return ImmutableCheckerResult.builder()
                .valid(visitor.valid())
                .errors(visitor.errors())
                .build();
    }

    private static class Visitor implements EventVisitor {
        private static final long DUMMY_VALUE = -1L;
        private static final int DUMMY_PROCESS = -1;

        private final Map<Integer, InvokeEvent> pendingReadForProcess = new HashMap<>();
        private final NavigableSet<OkEvent> acknowledgedReadsOverTime = new TreeSet<>(
                (first, second) -> Long.compare(first.time(), second.time()));

        private final List<Event> errors = new ArrayList<>();

        @Override
        public void visit(InfoEvent event) {
        }

        @Override
        public void visit(InvokeEvent event) {
            Integer process = event.process();
            pendingReadForProcess.put(process, event);
        }

        @Override
        public void visit(OkEvent event) {
            int process = event.process();

            InvokeEvent invoke = pendingReadForProcess.get(process);
            if (invoke != null) {
                validateTimestampHigherThanReadsCompletedBeforeInvoke(invoke, event);
            }

            pendingReadForProcess.remove(process);
            acknowledgedReadsOverTime.add(event);
        }

        @Override
        public void visit(FailEvent event) {
            Integer process = event.process();
            pendingReadForProcess.remove(process);
        }

        public boolean valid() {
            return errors.isEmpty();
        }

        public List<Event> errors() {
            return ImmutableList.copyOf(errors);
        }

        private void validateTimestampHigherThanReadsCompletedBeforeInvoke(InvokeEvent invoke, OkEvent event) {
            OkEvent lastAcknowledgedRead = lastAcknowledgedReadBefore(invoke.time());
            if (lastAcknowledgedRead != null && lastAcknowledgedRead.value() >= event.value()) {
                errors.add(lastAcknowledgedRead);
                errors.add(invoke);
                errors.add(event);
            }
        }

        private OkEvent lastAcknowledgedReadBefore(long time) {
            OkEvent dummyOkEvent = ImmutableOkEvent.builder()
                    .time(time)
                    .process(DUMMY_PROCESS)
                    .value(DUMMY_VALUE)
                    .build();
            return acknowledgedReadsOverTime.floor(dummyOkEvent);
        }
    }
}
