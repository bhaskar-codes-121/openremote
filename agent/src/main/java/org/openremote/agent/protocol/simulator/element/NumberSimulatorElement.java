/*
 * Copyright 2016, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.agent.protocol.simulator.element;

import org.openremote.model.ValidationFailure;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeType;
import org.openremote.model.simulator.SimulatorElement;
import org.openremote.model.value.Value;
import org.openremote.model.value.Values;

import java.util.List;

public class NumberSimulatorElement extends SimulatorElement {

    public enum ValueValidationFailure implements ValidationFailure {
        NUMBER_OUT_OF_RANGE
    }

    public static final String ELEMENT_NAME = "number";
    public static final String ELEMENT_NAME_RANGE = "range";

    final protected Integer min;
    final protected Integer max;

    public NumberSimulatorElement(AttributeRef attributeRef) {
        this(attributeRef, null, null);
    }

    public NumberSimulatorElement(AttributeRef attributeRef, Integer min, Integer max) {
        super(attributeRef, AttributeType.NUMBER);
        this.min = min;
        this.max = max;
    }

    public Integer getMin() {
        return min;
    }

    public Integer getMax() {
        return max;
    }

    @Override
    protected List<ValidationFailure> getValidationFailures(Value value) {
        List<ValidationFailure> failures = super.getValidationFailures(value);
        if ((getMin() != null && Values.getNumber(value).filter(v -> v < getMin()).isPresent())
            && (getMax() != null && Values.getNumber(value).filter(v -> v > getMax()).isPresent())) {
            failures.add(ValueValidationFailure.NUMBER_OUT_OF_RANGE);
        }
        return failures;
    }
}
