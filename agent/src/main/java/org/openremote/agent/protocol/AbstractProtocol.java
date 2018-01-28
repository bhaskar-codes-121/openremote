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
package org.openremote.agent.protocol;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.agent.protocol.filter.MessageFilter;
import org.openremote.container.Container;
import org.openremote.container.message.MessageBrokerContext;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.message.MessageBrokerSetupService;
import org.openremote.container.timer.TimerService;
import org.openremote.model.ValidationFailure;
import org.openremote.model.ValueHolder;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.asset.AssetMeta;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.agent.ProtocolConfiguration;
import org.openremote.model.asset.agent.ProtocolDescriptor;
import org.openremote.model.attribute.*;
import org.openremote.model.value.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINEST;

/**
 * Thread-safe base implementation for protocols.
 * <p>
 * Subclasses should use the {@link #withLock} and {@link #withLockReturning} methods
 * to guard critical sections when modifying shared state:
 * <blockquote><pre>{@code
 * withLock(() -> {
 *     // Critical section
 * });
 * }</pre></blockquote>
 * <blockquote><pre>{@code
 * return withLockReturning(() -> {
 *     // Critical section
 *     return ...;
 * });
 * }</pre></blockquote>
 */
public abstract class AbstractProtocol implements Protocol {

    protected static class LinkedProtocolInfo {

        final AssetAttribute protocolConfiguration;
        final Consumer<ConnectionStatus> connectionStatusConsumer;
        ConnectionStatus currentConnectionStatus;

        protected LinkedProtocolInfo(
            AssetAttribute protocolConfiguration,
            Consumer<ConnectionStatus> connectionStatusConsumer,
            ConnectionStatus currentConnectionStatus
        ) {
            this.protocolConfiguration = protocolConfiguration;
            this.connectionStatusConsumer = connectionStatusConsumer;
            this.currentConnectionStatus = currentConnectionStatus;
        }

        public AssetAttribute getProtocolConfiguration() {
            return protocolConfiguration;
        }

        public Consumer<ConnectionStatus> getConnectionStatusConsumer() {
            return connectionStatusConsumer;
        }

        public ConnectionStatus getCurrentConnectionStatus() {
            return currentConnectionStatus;
        }

        protected void setCurrentConnectionStatus(ConnectionStatus currentConnectionStatus) {
            this.currentConnectionStatus = currentConnectionStatus;
        }
    }

    private static final Logger LOG = Logger.getLogger(AbstractProtocol.class.getName());

    protected final Map<AttributeRef, AssetAttribute> linkedAttributes = new HashMap<>();
    protected final Map<AttributeRef, LinkedProtocolInfo> linkedProtocolConfigurations = new HashMap<>();
    protected final Map<AttributeRef, List<MessageFilter>> linkedAttributeFilters = new HashMap<>();
    protected final List<AttributeRef> locationLinkedAttributes = new ArrayList<>();
    protected MessageBrokerContext messageBrokerContext;
    protected ProducerTemplate producerTemplate;
    protected TimerService timerService;
    protected ProtocolExecutorService executorService;
    protected ProtocolAssetService assetService;

    // Provides exclusive access to shared protocol state
    protected final ReentrantLock lock = new ReentrantLock(true);

    @Override
    public void init(Container container) throws Exception {
        LOG.info("Initializing protocol: " + getProtocolName());
        timerService = container.getService(TimerService.class);
        executorService = container.getService(ProtocolExecutorService.class);
        assetService = container.getService(ProtocolAssetService.class);
    }

    @Override
    public void start(Container container) throws Exception {
        LOG.fine("Starting protocol: " + getProtocolName());
        this.messageBrokerContext = container.getService(MessageBrokerSetupService.class).getContext();
        this.producerTemplate = container.getService(MessageBrokerService.class).getProducerTemplate();

        withLock(() -> {
            try {
                messageBrokerContext.addRoutes(new RouteBuilder() {
                    @Override
                    public void configure() throws Exception {
                        from(ACTUATOR_TOPIC)
                            .routeId("Actuator-" + getProtocolName())
                            .process(exchange -> {
                                String protocolName = exchange.getIn().getHeader(ACTUATOR_TOPIC_TARGET_PROTOCOL, String.class);
                                if (!getProtocolName().equals(protocolName))
                                    return;
                                processLinkedAttributeWrite(exchange.getIn().getBody(AttributeEvent.class));
                            });
                    }
                });
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Override
    public void stop(Container container) throws Exception {
        withLock(() -> {
            linkedAttributes.clear();
            try {
                messageBrokerContext.stopRoute("Actuator-" + getProtocolName(), 1, TimeUnit.MILLISECONDS);
                messageBrokerContext.removeRoute("Actuator-" + getProtocolName());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Override
    public void linkProtocolConfiguration(AssetAttribute protocolConfiguration, Consumer<ConnectionStatus> statusConsumer) {
        withLock(() -> {
            LOG.finer("Linking protocol configuration to protocol '" + getProtocolName() + "': " + protocolConfiguration);
            linkedProtocolConfigurations.put(
                protocolConfiguration.getReferenceOrThrow(),
                new LinkedProtocolInfo(protocolConfiguration, statusConsumer, ConnectionStatus.CONNECTING)
            );
            doLinkProtocolConfiguration(protocolConfiguration);
        });
    }

    @Override
    public void unlinkProtocolConfiguration(AssetAttribute protocolConfiguration) {
        withLock(() -> {
            LOG.finer("Unlinking protocol configuration from protocol '" + getProtocolName() + "': " + protocolConfiguration);
            doUnlinkProtocolConfiguration(protocolConfiguration);
            linkedProtocolConfigurations.remove(protocolConfiguration.getReferenceOrThrow());
        });
    }

    @Override
    public void linkAttributes(Collection<AssetAttribute> attributes, AssetAttribute protocolConfiguration) {
        withLock(() -> {
            attributes.forEach(attribute -> {
                LOG.fine("Linking attribute to '" + getProtocolName() + "': " + attribute);
                AttributeRef attributeRef = attribute.getReferenceOrThrow();
                // Need to add to map before actual linking as protocols may want to update the value as part of
                // linking process and without entry in the map any update would be blocked
                linkedAttributes.put(attributeRef, attribute);

                Optional<List<MessageFilter>> messageFilters = Protocol.getLinkedAttributeMessageFilters(attribute);
                messageFilters.ifPresent(mFilters -> {
                    linkedAttributeFilters.put(attributeRef, mFilters);
                });

                attribute.getMetaItem(AssetMeta.LOCATION_LINK).ifPresent(metaItem -> {
                    locationLinkedAttributes.add(attribute.getReferenceOrThrow());
                });

                try {
                    doLinkAttribute(attribute, protocolConfiguration);
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Failed to link attribute to protocol: " + attribute, e);
                    linkedAttributes.remove(attributeRef);
                    linkedAttributeFilters.remove(attributeRef);
                    locationLinkedAttributes.remove(attributeRef);
                }
            });
        });
    }

    @Override
    public void unlinkAttributes(Collection<AssetAttribute> attributes, AssetAttribute protocolConfiguration) throws Exception {
        withLock(() ->
            attributes.forEach(attribute -> {
                LOG.fine("Unlinking attribute on '" + getProtocolName() + "': " + attribute);
                AttributeRef attributeRef = attribute.getReferenceOrThrow();
                linkedAttributes.remove(attributeRef);
                linkedAttributeFilters.remove(attributeRef);
                locationLinkedAttributes.remove(attributeRef);
                doUnlinkAttribute(attribute, protocolConfiguration);
            })
        );
    }

    /**
     * Gets a linked attribute by its attribute ref
     */
    protected AssetAttribute getLinkedAttribute(AttributeRef attributeRef) {
        return withLockReturning(() -> linkedAttributes.get(attributeRef));
    }

    /**
     * Get the protocol configuration that this attribute links to.
     */
    protected AssetAttribute getLinkedProtocolConfiguration(AssetAttribute attribute) {
        AttributeRef protocolConfigRef = AgentLink.getAgentLink(attribute).orElseThrow(() -> new IllegalStateException("Attribute is not linked to a protocol"));
        return getLinkedProtocolConfiguration(protocolConfigRef);
    }

    protected AssetAttribute getLinkedProtocolConfiguration(AttributeRef protocolConfigurationRef) {
        return withLockReturning(() -> {
            LinkedProtocolInfo linkedProtocolInfo = linkedProtocolConfigurations.get(protocolConfigurationRef);
            // Don't bother with null check if someone calls here with an attribute not linked to this protocol
            // then they're doing something wrong so fail hard and fast
            return linkedProtocolInfo.getProtocolConfiguration();
        });
    }

    protected void processLinkedAttributeWrite(AttributeEvent event) {
        LOG.finest("Processing linked attribute write on " + getProtocolName() + ": " + event);
        withLock(() -> {
            AssetAttribute attribute = linkedAttributes.get(event.getAttributeRef());
            if (attribute == null) {
                LOG.warning("Attribute doesn't exist on this protocol: " + event.getAttributeRef());
            } else {
                AssetAttribute protocolConfiguration = getLinkedProtocolConfiguration(attribute);
                processLinkedAttributeWrite(event, protocolConfiguration);
            }
        });
    }

    /**
     * Send an arbitrary {@link AttributeState} through the processing chain using the current system time as the
     * timestamp. Use {@link #updateLinkedAttribute} to publish new sensor values, which performs additional
     * verification and uses a different messaging queue.
     */
    protected void sendAttributeEvent(AttributeState state) {
        sendAttributeEvent(new AttributeEvent(state, timerService.getCurrentTimeMillis()));
    }

    /**
     * Send an arbitrary {@link AttributeEvent} through the processing chain. Use {@link #updateLinkedAttribute} to
     * publish new sensor values, which performs additional verification and uses a different messaging queue.
     */
    protected void sendAttributeEvent(AttributeEvent event) {
        withLock(() -> {
            // Don't allow updating linked attributes with this mechanism as it could cause an infinite loop
            if (linkedAttributes.containsKey(event.getAttributeRef())) {
                LOG.warning("Cannot update an attribute linked to the same protocol; use updateLinkedAttribute for that: " + event);
                return;
            }
            assetService.sendAttributeEvent(event);
        });
    }

    /**
     * Update the value of a linked attribute. Call this to publish new sensor values. This will apply any
     * {@link MessageFilter}s that have been set for the {@link Attribute} against the {@link AttributeState#value}
     * before sending on the sensor queue.
     */
    @SuppressWarnings("unchecked")
    protected void updateLinkedAttribute(final AttributeState finalState, long timestamp) {
        withLock(() -> {
            AttributeState state = finalState;
            AssetAttribute attribute = linkedAttributes.get(state.getAttributeRef());

            if (attribute == null) {
                LOG.severe("Update linked attribute called for un-linked attribute: " + state);
                return;
            }

            if (state.getValue().isPresent()) {
                List<MessageFilter> filters;
                Value value = state.getValue().get();

                filters = linkedAttributeFilters.get(state.getAttributeRef());

                if (filters != null) {
                    LOG.fine("Applying message filters to sensor value...");

                    for (MessageFilter filter : filters) {
                        if (filter.getMessageType() != value.getType().getModelType()) {
                            LOG.fine("Message filter type '" + filter.getMessageType().getName()
                                + "' is not compatible with actual message type '" + value.getType().getModelType().getName()
                                + "': " + filter.getClass().getName());
                            value = null;
                        } else {
                            try {
                                LOG.finest("Applying message filter: " + filter.getClass().getName());
                                value = filter.process(value);
                            } catch (Exception e) {
                                LOG.log(
                                    Level.SEVERE,
                                    "Message filter threw and exception during processing of message: "
                                        + filter.getClass().getName(),
                                    e);
                                value = null;
                            }
                        }

                        if (value == null) {
                            break;
                        }
                    }
                }

                // Do basic value conversion
                Optional<ValueType> attributeValueType = attribute.getType().map(AttributeType::getValueType);

                if (value != null && attributeValueType.isPresent()) {
                    if (attributeValueType.get() != value.getType()) {
                        LOG.fine("Converting value: " + value.getType() + " -> " + attributeValueType.get());
                        Optional<Value> convertedValue = Values.convert(value, attributeValueType.get());
                        if (!convertedValue.isPresent()) {
                            LOG.warning("Failed to convert value: " + value.getType() + " -> " + attributeValueType.get());
                        } else {
                            value = convertedValue.get();
                        }
                    }
                }

                state = new AttributeState(state.getAttributeRef(), value);
            }
            AttributeEvent attributeEvent = new AttributeEvent(state, timestamp);
            LOG.fine("Sending on sensor queue: " + attributeEvent);
            producerTemplate.sendBodyAndHeader(SENSOR_QUEUE, attributeEvent, Protocol.SENSOR_QUEUE_SOURCE_PROTOCOL, getProtocolName());

            if (locationLinkedAttributes.contains(state.getAttributeRef())) {

                // Check value type is compatible
                Point location = state.getValue().map(value -> {
                    if (value.getType() != ValueType.ARRAY) {
                        LOG.warning("Location linked attribute type is not an array");
                        return null;
                    }

                    Optional<List<NumberValue>> coordinates = Values.getArrayElements((ArrayValue) value, NumberValue.class, false, false);
                    if (!coordinates.isPresent()
                        || coordinates.get().size() != 2
                        || Math.abs(coordinates.get().get(0).getNumber()) > 180
                        || Math.abs(coordinates.get().get(1).getNumber()) > 90) {
                        LOG.warning("Location linked attribute value must contain longitude then latitude in a 2 value number array");
                        return null;
                    }

                    try {
                        return new GeometryFactory().createPoint(
                            new Coordinate(coordinates.get().get(0).getNumber(), coordinates.get().get(1).getNumber())
                        );
                    } catch (Exception e) {
                        return null;
                    }
                }).orElse(null);

                updateAssetLocation(state.getAttributeRef().getEntityId(), location);
            }
        });
    }

    /**
     * Update the value of a linked attribute, with the current system time as event time see
     * {@link #updateLinkedAttribute(AttributeState, long)} for more details.
     */
    protected void updateLinkedAttribute(AttributeState state) {
        updateLinkedAttribute(state, timerService.getCurrentTimeMillis());
    }

    protected void updateAssetLocation(String assetId, Point location) {
        withLock(() -> assetService.updateAssetLocation(assetId, location));
    }

    /**
     * Update a linked protocol configuration; allows protocols to reconfigure their own protocol configurations to
     * persist changing data e.g. authorization tokens. First this clones the existing protocolConfiguration and calls
     * the consumer to perform the modification.
     */
    protected void updateLinkedProtocolConfiguration(AssetAttribute protocolConfiguration, Consumer<AssetAttribute> protocolUpdater) {
        withLock(() -> {
            // Clone the protocol configuration rather than modify this one
            AssetAttribute modifiedProtocolConfiguration = protocolConfiguration.deepCopy();
            protocolUpdater.accept(modifiedProtocolConfiguration);
            assetService.updateProtocolConfiguration(modifiedProtocolConfiguration);
        });
    }

    /**
     * Update the runtime status of a protocol configuration by its attribute ref
     */
    protected void updateStatus(AttributeRef protocolRef, ConnectionStatus connectionStatus) {
        withLock(() -> {
            LinkedProtocolInfo protocolInfo = linkedProtocolConfigurations.get(protocolRef);
            if (protocolInfo != null) {
                protocolInfo.getConnectionStatusConsumer().accept(connectionStatus);
                protocolInfo.setCurrentConnectionStatus(connectionStatus);
            }
        });
    }

    /**
     * Gets the current runtime status of a protocol configuration.
     */
    protected ConnectionStatus getStatus(AssetAttribute protocolConfiguration) {
        return withLockReturning(() -> {
            LinkedProtocolInfo linkedProtocolInfo = linkedProtocolConfigurations.get(protocolConfiguration.getReferenceOrThrow());
            return linkedProtocolInfo.getCurrentConnectionStatus();
        });
    }

    @Override
    public ProtocolDescriptor getProtocolDescriptor() {
        return new ProtocolDescriptor(
            getProtocolName(),
            getProtocolDisplayName(),
            getVersion(),
            this instanceof ProtocolConfigurationDiscovery,
            this instanceof ProtocolConfigurationImport,
            this instanceof ProtocolLinkedAttributeDiscovery,
            this instanceof ProtocolLinkedAttributeImport,
            getProtocolConfigurationTemplate(),
            getProtocolConfigurationMetaItemDescriptors(),
            getLinkedAttributeMetaItemDescriptors()
        );
    }

    @Override
    public AssetAttribute getProtocolConfigurationTemplate() {
        return ProtocolConfiguration.initProtocolConfiguration(new AssetAttribute(), getProtocolName());
    }

    @Override
    public AttributeValidationResult validateProtocolConfiguration(AssetAttribute protocolConfiguration) {
        AttributeValidationResult result = new AttributeValidationResult(protocolConfiguration.getName().orElse(""));

        if (!ProtocolConfiguration.isProtocolConfiguration(protocolConfiguration)) {
            result.addMetaFailure(new ValidationFailure(MetaItem.MetaItemFailureReason.META_ITEM_MISSING, AssetMeta.PROTOCOL_CONFIGURATION.name()));
        }
        if (!ProtocolConfiguration.isValidProtocolName(protocolConfiguration.getValueAsString().orElse(null))) {
            result.addAttributeFailure(new ValidationFailure(ValueHolder.ValueFailureReason.VALUE_INVALID));
        }
        return result;
    }

    /**
     * @return Defaults to 6 seconds, should be longer than it takes the router to be enabled/disabled.
     */
    protected int getLockTimeoutMillis() {
        return 6000;
    }

    protected void withLock(Runnable runnable) {
        withLockReturning(() -> {
            runnable.run();
            return null;
        });
    }

    protected <R> R withLockReturning(Supplier<R> supplier) {
        try {
            if (lock.tryLock(getLockTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                LOG.finest("+ Acquired lock on: " + getProtocolName());
                return supplier.get();
            } else {
                throw new IllegalStateException(
                    "Could not acquire lock after waiting " + getLockTimeoutMillis() + "ms on: " + getProtocolName()
                );
            }
        } catch (InterruptedException ex) {
            LOG.log(FINEST, "Interrupted while waiting for lock on: " + getProtocolName());
            return null;
        } finally {
            LOG.finest("- Releasing lock on: " + getProtocolName());
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
            '}';
    }

    protected List<MetaItemDescriptor> getProtocolConfigurationMetaItemDescriptors() {
        return Collections.emptyList();
    }

    protected List<MetaItemDescriptor> getLinkedAttributeMetaItemDescriptors() {
        return Collections.emptyList();
    }

    /**
     * Link the protocol configuration.
     */
    abstract protected void doLinkProtocolConfiguration(AssetAttribute protocolConfiguration);

    /**
     * Unlink the protocol configuration.
     */
    abstract protected void doUnlinkProtocolConfiguration(AssetAttribute protocolConfiguration);

    /**
     * Link an attribute to its linked protocol configuration.
     */
    abstract protected void doLinkAttribute(AssetAttribute attribute, AssetAttribute protocolConfiguration);

    /**
     * Unlink an attribute from its linked protocol configuration.
     */
    abstract protected void doUnlinkAttribute(AssetAttribute attribute, AssetAttribute protocolConfiguration);

    /**
     * Attribute event (write) has been requested for an attribute linked to the specified protocol configuration.
     */
    abstract protected void processLinkedAttributeWrite(AttributeEvent event, AssetAttribute protocolConfiguration);
}
