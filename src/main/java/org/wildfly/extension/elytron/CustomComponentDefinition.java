/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.extension.elytron;

import static org.wildfly.extension.elytron.ClassLoadingAttributeDefinitions.CLASS_NAME;
import static org.wildfly.extension.elytron.ClassLoadingAttributeDefinitions.MODULE;
import static org.wildfly.extension.elytron.ClassLoadingAttributeDefinitions.SLOT;
import static org.wildfly.extension.elytron.ElytronDefinition.commonDependencies;
import static org.wildfly.extension.elytron.ElytronDescriptionConstants.KEY;
import static org.wildfly.extension.elytron.ElytronDescriptionConstants.PROPERTY;
import static org.wildfly.extension.elytron.ElytronDescriptionConstants.VALUE;
import static org.wildfly.extension.elytron.ElytronExtension.asStringIfDefined;

import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.AttributeMarshaller;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.RestartParentWriteAttributeHandler;
import org.jboss.as.controller.ServiceRemoveStepHandler;
import org.jboss.as.controller.SimpleMapAttributeDefinition;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;


/**
 * A {@link SimpleResourceDefinition} for a custom configurable component.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
class CustomComponentDefinition<T> extends SimpleResourceDefinition {

    static final SimpleMapAttributeDefinition CONFIGURATION = new SimpleMapAttributeDefinition.Builder(ElytronDescriptionConstants.CONFIGURATION, ModelType.STRING, true)
        .setAttributeMarshaller(new AttributeMarshaller() {

            @Override
            public void marshallAsElement(AttributeDefinition attribute, ModelNode resourceModel, boolean marshallDefault,
                                XMLStreamWriter writer) throws XMLStreamException {
                resourceModel = resourceModel.get(attribute.getName());
                if (resourceModel.isDefined()) {
                    writer.writeStartElement(attribute.getName());
                    for (ModelNode property : resourceModel.asList()) {
                        writer.writeEmptyElement(PROPERTY);
                        writer.writeAttribute(KEY, property.asProperty().getName());
                        writer.writeAttribute(VALUE, property.asProperty().getValue().asString());
                        }
                    writer.writeEndElement();
                    }
                }

            })
        .build();

    private final Class<T> serviceType;
    private final RuntimeCapability<Void> runtimeCapability;
    private final String pathKey;

    private static final AttributeDefinition[] ATTRIBUTES = {MODULE, SLOT, CLASS_NAME, CONFIGURATION};

    CustomComponentDefinition(Class<T> serviceType, RuntimeCapability<Void> runtimeCapability, String pathKey) {
        super(addAddRemoveHandlers(new Parameters(PathElement.pathElement(pathKey), ElytronExtension.getResourceDescriptionResolver(pathKey))
            .setAddRestartLevel(OperationEntry.Flag.RESTART_RESOURCE_SERVICES)
            .setRemoveRestartLevel(OperationEntry.Flag.RESTART_RESOURCE_SERVICES), runtimeCapability, serviceType));

        this.serviceType = serviceType;
        this.runtimeCapability = runtimeCapability;
        this.pathKey = pathKey;
    }

    private static <T> Parameters addAddRemoveHandlers(Parameters parameters, RuntimeCapability<Void> runtimeCapability, Class<T> serviceType) {
        AbstractAddStepHandler add = new ComponentAddHandler<T>(runtimeCapability, serviceType);
        OperationStepHandler remove = new ComponentRemoveHandler<T>(add, runtimeCapability, serviceType);

        parameters.setAddHandler(add);
        parameters.setRemoveHandler(remove);

        return parameters;
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        WriteAttributeHandler<T> writeHandler = new WriteAttributeHandler<T>(serviceType, runtimeCapability, pathKey);
        for (AttributeDefinition current : ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(current, null, writeHandler);
        }
    }

    private static class ComponentAddHandler<T> extends AbstractAddStepHandler {

        private final RuntimeCapability<Void> runtimeCapability;
        private final Class<T> serviceType;

        private ComponentAddHandler(RuntimeCapability<Void> runtimeCapability, Class<T> serviceType) {
            super(runtimeCapability, ATTRIBUTES);
            this.runtimeCapability = runtimeCapability;
            this.serviceType = serviceType;
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model)
                throws OperationFailedException {
            ServiceTarget serviceTarget = context.getServiceTarget();
            RuntimeCapability<Void> runtimeCapability = this.runtimeCapability.fromBaseCapability(context.getCurrentAddressValue());
            ServiceName componentName = runtimeCapability.getCapabilityServiceName(serviceType);

            String module = asStringIfDefined(context, MODULE, model);
            String slot = asStringIfDefined(context, SLOT, model);
            String className = CLASS_NAME.resolveModelAttribute(context, model).asString();

            final Map<String, String> configurationMap;
            ModelNode configuration = CONFIGURATION.resolveModelAttribute(context, model);
            if (configuration.isDefined()) {
                configurationMap = new HashMap<String, String>();
                configuration.asPropertyList().forEach((Property p) -> configurationMap.put(p.getName(), p.getValue().asString()));
            } else {
                configurationMap = null;
            }

            CustomComponentService<T> customComponentService = new CustomComponentService<T>(serviceType, module, slot, className, configurationMap);

            ServiceBuilder<T> serviceBuilder = serviceTarget.addService(componentName, customComponentService);
            commonDependencies(serviceBuilder)
                .setInitialMode(Mode.ACTIVE)
                .install();
        }

    }

    private static class ComponentRemoveHandler<T> extends ServiceRemoveStepHandler {

        private final RuntimeCapability<Void> runtimeCapability;
        private final Class<T> serviceType;

        public ComponentRemoveHandler(AbstractAddStepHandler addOperation, RuntimeCapability<Void> runtimeCapability, Class<T> serviceType) {
            super(addOperation, runtimeCapability);
            this.runtimeCapability = runtimeCapability;
            this.serviceType = serviceType;
        }

        @Override
        protected ServiceName serviceName(String name) {
            RuntimeCapability<?> dynamicCapability = runtimeCapability.fromBaseCapability(name);
            return dynamicCapability.getCapabilityServiceName(serviceType);
        }
    }

    private static class WriteAttributeHandler<T> extends RestartParentWriteAttributeHandler {

        private final RuntimeCapability<?> runtimeCapability;
        private final Class<T> serviceType;

        WriteAttributeHandler(Class<T> serviceType, RuntimeCapability<?> runtimeCapability, String pathKey) {
            super(pathKey, ATTRIBUTES);
            this.serviceType = serviceType;
            this.runtimeCapability = runtimeCapability;
        }

        @Override
        protected ServiceName getParentServiceName(PathAddress pathAddress) {
            return runtimeCapability.fromBaseCapability(pathAddress.getLastElement().getValue()).getCapabilityServiceName(serviceType);
        }

    }

}
