/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
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
package apoc.atomic;

import apoc.atomic.util.AtomicUtils;
import apoc.util.ArrayBackedList;
import apoc.util.MapUtil;
import apoc.util.Util;
import org.apache.commons.lang3.ArrayUtils;
import org.neo4j.exceptions.Neo4jException;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.*;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * @author AgileLARUS
 * @since 20-06-17
 */
public class Atomic {

    @Context
    public Transaction tx;

    /**
     * increment a property's value
     */
    @Procedure(name= "apoc.atomic.add", mode = Mode.WRITE)
    @Description("Sets the given property to the sum of itself and the number value.\n" +
            "The procedure then sets the property to the returned sum.")
    public Stream<AtomicResults> add(@Name("container") Object container, @Name("propertyName") String property, @Name("number") Number number, @Name(value = "times", defaultValue = "5") Long times) {
        checkIsEntity(container);
        final Number[] newValue = new Number[1];
        final Number[] oldValue = new Number[1];
        Entity entity = Util.rebind(tx, (Entity) container);

        final ExecutionContext executionContext = new ExecutionContext(tx, entity);
        retry(executionContext, (context) -> {
            oldValue[0] = (Number) entity.getProperty(property);
            newValue[0] = AtomicUtils.sum((Number) entity.getProperty(property), number);
            entity.setProperty(property, newValue[0]);
            return context.entity.getProperty(property);
        }, times);

        return Stream.of(new AtomicResults(entity,property, oldValue[0], newValue[0]));
    }

    /**
     * decrement a property's value
     */
    @Procedure(name = "apoc.atomic.subtract", mode = Mode.WRITE)
    @Description("Sets the property of a value to itself minus the given number value.\n" +
            "The procedure then sets the property to the returned sum.")
    public Stream<AtomicResults> subtract(@Name("container") Object container, @Name("propertyName") String property, @Name("number") Number number, @Name(value = "times", defaultValue = "5") Long times) {
        checkIsEntity(container);
        Entity entity = Util.rebind(tx, (Entity) container);
        final Number[] newValue = new Number[1];
        final Number[] oldValue = new Number[1];

        final ExecutionContext executionContext = new ExecutionContext(tx, entity);
        retry(executionContext, (context) -> {
            oldValue[0] = (Number) entity.getProperty(property);
            newValue[0] = AtomicUtils.sub((Number) entity.getProperty(property), number);
            entity.setProperty(property, newValue[0]);
            return context.entity.getProperty(property);
        }, times);

        return Stream.of(new AtomicResults(entity, property, oldValue[0], newValue[0]));
    }

    /**
     * concat a property's value
     */
    @Procedure(name = "apoc.atomic.concat", mode = Mode.WRITE)
    @Description("Sets the given property to the concatenation of itself and the string value.\n" +
            "The procedure then sets the property to the returned string.")
    public Stream<AtomicResults> concat(@Name("container") Object container, @Name("propertyName") String property, @Name("string") String string, @Name(value = "times", defaultValue = "5") Long times) {
        checkIsEntity(container);
        Entity entity = Util.rebind(tx, (Entity) container);
        final String[] newValue = new String[1];
        final String[] oldValue = new String[1];

        final ExecutionContext executionContext = new ExecutionContext(tx, entity);
        retry(executionContext, (context) -> {
            oldValue[0] = entity.getProperty(property).toString();
            newValue[0] = oldValue[0].concat(string);
            entity.setProperty(property, newValue[0]);

            return context.entity.getProperty(property);
        }, times);

        return Stream.of(new AtomicResults(entity, property, oldValue[0], newValue[0]));
    }

    /**
     * insert a value into an array property value
     */
    @Procedure(name = "apoc.atomic.insert", mode = Mode.WRITE)
    @Description("Inserts a value at position into the array value of a property.\n" +
            "The procedure then sets the result back on the property.")
    public Stream<AtomicResults> insert(@Name("container") Object container, @Name("propertyName") String property, @Name("position") Long position, @Name("value") Object value, @Name(value = "times", defaultValue = "5") Long times) {
        checkIsEntity(container);
        Entity entity = Util.rebind(tx, (Entity) container);
        final Object[] oldValue = new Object[1];
        final Object[] newValue = new Object[1];
        final ExecutionContext executionContext = new ExecutionContext(tx, entity);

        retry(executionContext, (context) -> {
            oldValue[0] = entity.getProperty(property);
            List<Object> values = insertValueIntoArray(entity.getProperty(property), position, value);
            Class clazz;
            try {
                clazz = Class.forName(values.toArray()[0].getClass().getName());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            newValue[0] = Array.newInstance(clazz, values.size());
            try {
                System.arraycopy(values.toArray(), 0, newValue[0], 0, values.size());
            } catch (Exception e) {
                String message = "Property's array value has type: " + values.toArray()[0].getClass().getName() + ", and your value to insert has type: " + value.getClass().getName();
                throw new ArrayStoreException(message);
            }
            entity.setProperty(property, newValue[0]);
            return context.entity.getProperty(property);
        }, times);

        return Stream.of(new AtomicResults(entity, property, oldValue[0], newValue[0]));
    }

    /**
     * remove a value into an array property value
     */
    @Procedure(name = "apoc.atomic.remove", mode = Mode.WRITE)
    @Description("Removes the element at position from the array value of a property.\n" +
            "The procedure then sets the property to the resulting array value.")
    public Stream<AtomicResults> remove(@Name("container") Object container, @Name("propertyName") String property, @Name("position") Long position, @Name(value = "times", defaultValue = "5") Long times) {
        checkIsEntity(container);
        Entity entity = Util.rebind(tx, (Entity) container);
        final Object[] oldValue = new Object[1];
        final Object[] newValue = new Object[1];
        final ExecutionContext executionContext = new ExecutionContext(tx, entity);

        retry(executionContext, (context) -> {
            Object[] arrayBackedList = new ArrayBackedList(entity.getProperty(property)).toArray();

            oldValue[0] = arrayBackedList;
            if(position > arrayBackedList.length || position < 0) {
                throw new RuntimeException("Position " + position + " is out of range for array of length " + arrayBackedList.length);
            }
            Object[] newArray = ArrayUtils.addAll(Arrays.copyOfRange(arrayBackedList, 0, position.intValue()), Arrays.copyOfRange(arrayBackedList, position.intValue() +1, arrayBackedList.length));
            Class clazz;
            try {
                clazz = Class.forName(arrayBackedList[0].getClass().getName());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            /*it's not possible to return directly the newArray, we have to create a new array with the specific class*/
            newValue[0] = Array.newInstance(clazz, newArray.length);
            System.arraycopy(newArray, 0, newValue[0], 0, newArray.length);
            entity.setProperty(property, newValue[0]);

            return context.entity.getProperty(property);
        }, times);

        return Stream.of(new AtomicResults(entity, property, oldValue[0], newValue[0]));
    }

    /**
     * update the property's value
     */
    @Procedure(name = "apoc.atomic.update", mode = Mode.WRITE)
    @Description("Updates the value of a property with a Cypher operation.")
    public Stream<AtomicResults> update(@Name("container") Object nodeOrRelationship, @Name("propertyName") String property, @Name("operation") String operation, @Name(value = "times", defaultValue = "5") Long times)  {
        checkIsEntity(nodeOrRelationship);
        Entity entity = Util.rebind(tx, (Entity) nodeOrRelationship);
        final Object[] oldValue = new Object[1];
        final ExecutionContext executionContext = new ExecutionContext(tx, entity);

        retry(executionContext, (context) -> {
            oldValue[0] = entity.getProperty(property);
            String statement = "WITH $container as n with n set n." + property + "=" + operation + ";";
            Map<String, Object> properties = MapUtil.map("container", entity);
            return context.tx.execute(statement, properties);
        }, times);

        return Stream.of(new AtomicResults(entity,property,oldValue[0],entity.getProperty(property)));
    }

    private static class ExecutionContext {
        private final Transaction tx;

        private final Entity entity;

        public ExecutionContext(Transaction tx, Entity entity){
            this.tx = tx;
            this.entity = entity;
        }
    }

    private List<Object> insertValueIntoArray(Object oldValue, Long position, Object value) {
        List<Object> values = new ArrayList<>();
        if (oldValue.getClass().isArray())
            values.addAll(new ArrayBackedList(oldValue));
        else
            values.add(oldValue);
        if (position > values.size())
            values.add(value);
        else
            values.add(position.intValue(), value);
        return values;
    }

    private void retry(ExecutionContext executionContext, Function<ExecutionContext, Object> work, Long times){
        try {
            tx.acquireWriteLock(executionContext.entity);
            work.apply(executionContext);
        } catch (Neo4jException|NotFoundException|AssertionError e) {
            if (times > 0) {
                retry(executionContext, work, times-1);
            } else {
                throw e;
            }
        }
    }

    private void checkIsEntity(Object container) {
        if (!(container instanceof Entity)) throw new RuntimeException("You Must pass Node or Relationship");
    }

    public class AtomicResults {
        public Object container;
        public String property;
        public Object oldValue;
        public Object newValue;

        public AtomicResults(Object container, String property, Object oldValue, Object newValue) {
            this.container = container;
            this.property = property;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }
}
