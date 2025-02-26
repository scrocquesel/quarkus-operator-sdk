package io.quarkiverse.operatorsdk.deployment;

import static io.quarkiverse.operatorsdk.common.ClassLoadingUtils.instantiate;
import static io.quarkiverse.operatorsdk.common.ClassLoadingUtils.loadClass;
import static io.quarkiverse.operatorsdk.common.Constants.CONTROLLER_CONFIGURATION;
import static io.quarkus.arc.processor.DotNames.APPLICATION_SCOPED;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.ReconcilerUtils;
import io.javaoperatorsdk.operator.api.config.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.config.dependent.DependentResourceConfigurationResolver;
import io.javaoperatorsdk.operator.api.reconciler.MaxReconciliationInterval;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentConverter;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResourceConfig;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;
import io.javaoperatorsdk.operator.processing.dependent.workflow.ManagedWorkflow;
import io.javaoperatorsdk.operator.processing.dependent.workflow.ManagedWorkflowFactory;
import io.javaoperatorsdk.operator.processing.event.rate.RateLimiter;
import io.javaoperatorsdk.operator.processing.event.source.controller.ResourceEventFilter;
import io.javaoperatorsdk.operator.processing.event.source.filter.GenericFilter;
import io.javaoperatorsdk.operator.processing.event.source.filter.OnAddFilter;
import io.javaoperatorsdk.operator.processing.event.source.filter.OnUpdateFilter;
import io.javaoperatorsdk.operator.processing.retry.GenericRetry;
import io.javaoperatorsdk.operator.processing.retry.Retry;
import io.quarkiverse.operatorsdk.common.*;
import io.quarkiverse.operatorsdk.runtime.*;
import io.quarkiverse.operatorsdk.runtime.QuarkusControllerConfiguration.DefaultRateLimiter;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.util.JandexUtil;

@SuppressWarnings("rawtypes")
class QuarkusControllerConfigurationBuilder {

    static final Logger log = Logger.getLogger(QuarkusControllerConfigurationBuilder.class.getName());

    private static final KubernetesDependentConverter KUBERNETES_DEPENDENT_CONVERTER = new KubernetesDependentConverter() {
        @Override
        @SuppressWarnings("unchecked")
        public KubernetesDependentResourceConfig configFrom(
                KubernetesDependent configAnnotation,
                ControllerConfiguration parentConfiguration, Class originatingClass) {
            final var original = super.configFrom(configAnnotation, parentConfiguration, originatingClass);
            // make the configuration bytecode-serializable
            return new QuarkusKubernetesDependentResourceConfig(original.namespaces(),
                    original.labelSelector(),
                    original.wereNamespacesConfigured(),
                    original.getResourceDiscriminator(), original.onAddFilter(),
                    original.onUpdateFilter(), original.onDeleteFilter(), original.genericFilter());
        }
    };
    static {
        // register Quarkus-specific converter for Kubernetes dependent resources
        DependentResourceConfigurationResolver.registerConverter(KubernetesDependentResource.class,
                KUBERNETES_DEPENDENT_CONVERTER);
    }

    private final BuildProducer<AdditionalBeanBuildItem> additionalBeans;
    private final IndexView index;
    private final LiveReloadBuildItem liveReload;

    private final BuildTimeOperatorConfiguration buildTimeConfiguration;

    public QuarkusControllerConfigurationBuilder(
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            IndexView index, LiveReloadBuildItem liveReload,
            BuildTimeOperatorConfiguration buildTimeConfiguration) {
        this.additionalBeans = additionalBeans;
        this.index = index;
        this.liveReload = liveReload;
        this.buildTimeConfiguration = buildTimeConfiguration;
    }

    @SuppressWarnings("unchecked")
    QuarkusControllerConfiguration build(ReconcilerAugmentedClassInfo reconcilerInfo,
            Map<String, AnnotationConfigurableAugmentedClassInfo> configurableInfos) {

        // retrieve the reconciler's name
        final var info = reconcilerInfo.classInfo();
        final var reconcilerClassName = info.toString();
        final String name = reconcilerInfo.nameOrFailIfUnset();

        // create Reconciler bean
        additionalBeans.produce(
                AdditionalBeanBuildItem.builder()
                        .addBeanClass(reconcilerClassName)
                        .setUnremovable()
                        .setDefaultScope(APPLICATION_SCOPED)
                        .build());

        // check if we need to regenerate the configuration for this controller
        final var changeInformation = liveReload.getChangeInformation();
        QuarkusControllerConfiguration configuration = null;
        var storedConfigurations = liveReload.getContextObject(
                ContextStoredControllerConfigurations.class);
        if (liveReload.isLiveReload() && storedConfigurations != null) {
            // check if we need to regenerate the configuration for this controller
            final var changedClasses = changeInformation == null ? Collections.<String> emptySet()
                    : changeInformation.getChangedClasses();
            final var changedResources = liveReload.getChangedResources();
            configuration = storedConfigurations.configurationOrNullIfNeedGeneration(reconcilerClassName,
                    changedClasses,
                    changedResources);
        }

        if (configuration == null) {
            // extract the configuration from annotation and/or external configuration
            final var controllerAnnotation = info.declaredAnnotation(CONTROLLER_CONFIGURATION);

            final var configExtractor = new BuildTimeHybridControllerConfiguration(buildTimeConfiguration,
                    buildTimeConfiguration.controllers.get(name),
                    controllerAnnotation);

            // deal with event filters
            ResourceEventFilter finalFilter = null;
            final var eventFilterTypes = ConfigurationUtils.annotationValueOrDefault(
                    controllerAnnotation, "eventFilters",
                    AnnotationValue::asClassArray, () -> new Type[0]);
            for (Type filterType : eventFilterTypes) {
                final var filterClass = loadClass(filterType.name().toString(),
                        ResourceEventFilter.class);
                final var filter = instantiate(filterClass);
                finalFilter = finalFilter == null ? filter : finalFilter.and(filter);
            }

            Duration maxReconciliationInterval = null;
            OnAddFilter onAddFilter = null;
            OnUpdateFilter onUpdateFilter = null;
            GenericFilter genericFilter = null;
            Class<? extends Retry> retryClass = GenericRetry.class;
            Class<?> retryConfigurationClass = null;
            Class<? extends RateLimiter> rateLimiterClass = DefaultRateLimiter.class;
            Class<?> rateLimiterConfigurationClass = null;
            if (controllerAnnotation != null) {
                final var intervalFromAnnotation = ConfigurationUtils.annotationValueOrDefault(
                        controllerAnnotation, "maxReconciliationInterval", AnnotationValue::asNested,
                        () -> null);
                final var interval = ConfigurationUtils.annotationValueOrDefault(
                        intervalFromAnnotation, "interval", AnnotationValue::asLong,
                        () -> MaxReconciliationInterval.DEFAULT_INTERVAL);
                final var timeUnit = (TimeUnit) ConfigurationUtils.annotationValueOrDefault(
                        intervalFromAnnotation,
                        "timeUnit",
                        av -> TimeUnit.valueOf(av.asEnum()),
                        () -> TimeUnit.HOURS);
                if (interval > 0) {
                    maxReconciliationInterval = Duration.of(interval, timeUnit.toChronoUnit());
                }

                onAddFilter = ConfigurationUtils.instantiateImplementationClass(
                        controllerAnnotation, "onAddFilter", OnAddFilter.class, OnAddFilter.class, true, index);
                onUpdateFilter = ConfigurationUtils.instantiateImplementationClass(
                        controllerAnnotation, "onUpdateFilter", OnUpdateFilter.class, OnUpdateFilter.class,
                        true, index);
                genericFilter = ConfigurationUtils.instantiateImplementationClass(
                        controllerAnnotation, "genericFilter", GenericFilter.class, GenericFilter.class,
                        true, index);
                retryClass = ConfigurationUtils.annotationValueOrDefault(controllerAnnotation,
                        "retry", av -> loadClass(av.asClass().name().toString(), Retry.class), () -> GenericRetry.class);
                final var retryConfigurableInfo = configurableInfos.get(retryClass.getName());
                retryConfigurationClass = getConfigurationAnnotationClass(reconcilerInfo, retryConfigurableInfo);
                rateLimiterClass = ConfigurationUtils.annotationValueOrDefault(
                        controllerAnnotation,
                        "rateLimiter", av -> loadClass(av.asClass().name().toString(), RateLimiter.class),
                        () -> DefaultRateLimiter.class);
                final var rateLimiterConfigurableInfo = configurableInfos.get(rateLimiterClass.getName());
                rateLimiterConfigurationClass = getConfigurationAnnotationClass(reconcilerInfo,
                        rateLimiterConfigurableInfo);
            }

            // extract the namespaces
            var namespaces = configExtractor.namespaces();
            final boolean wereNamespacesSet;
            if (namespaces == null) {
                namespaces = io.javaoperatorsdk.operator.api.reconciler.Constants.DEFAULT_NAMESPACES_SET;
                wereNamespacesSet = false;
            } else {
                wereNamespacesSet = true;
            }

            // create the configuration
            final ReconciledAugmentedClassInfo<?> primaryInfo = reconcilerInfo.associatedResourceInfo();
            final var primaryAsResource = primaryInfo.asResourceTargeting();
            final var resourceClass = primaryInfo.loadAssociatedClass();
            final String resourceFullName = primaryAsResource.fullResourceName();
            // initialize dependent specs
            final Map<String, DependentResourceSpecMetadata> dependentResources;
            final var dependentResourceInfos = reconcilerInfo.getDependentResourceInfos();
            final var hasDependents = !dependentResourceInfos.isEmpty();
            if (hasDependents) {
                dependentResources = new HashMap<>(dependentResourceInfos.size());
            } else {
                dependentResources = Collections.emptyMap();
            }
            configuration = new QuarkusControllerConfiguration(
                    reconcilerClassName,
                    name,
                    resourceFullName,
                    primaryAsResource.version(),
                    configExtractor.generationAware(),
                    resourceClass,
                    namespaces,
                    wereNamespacesSet,
                    getFinalizer(controllerAnnotation, resourceFullName),
                    getLabelSelector(controllerAnnotation),
                    primaryAsResource.hasNonVoidStatus(),
                    finalFilter,
                    maxReconciliationInterval,
                    onAddFilter, onUpdateFilter, genericFilter, retryClass, retryConfigurationClass,
                    rateLimiterClass,
                    rateLimiterConfigurationClass, dependentResources, null);

            if (hasDependents) {
                QuarkusControllerConfiguration finalConfiguration = configuration;
                dependentResourceInfos.forEach(dependent -> {
                    final var spec = createDependentResourceSpec(dependent, index,
                            finalConfiguration);
                    final var dependentName = dependent.classInfo().name();
                    dependentResources.put(dependentName.toString(), spec);

                    final var dependentTypeName = dependentName.toString();
                    additionalBeans.produce(
                            AdditionalBeanBuildItem.builder()
                                    .addBeanClass(dependentTypeName)
                                    .setUnremovable()
                                    .setDefaultScope(APPLICATION_SCOPED)
                                    .build());
                });
            }

            // compute workflow and set it (originally set to null in constructor)
            final ManagedWorkflow workflow;
            if (hasDependents) {
                // make workflow bytecode serializable
                final var original = ManagedWorkflowFactory.DEFAULT.workflowFor(configuration);
                workflow = new QuarkusManagedWorkflow<>(original.getOrderedSpecs(),
                        original.hasCleaner());
            } else {
                workflow = QuarkusManagedWorkflow.noOpManagedWorkflow;
            }
            configuration.setWorkflow(workflow);

            log.infov(
                    "Processed ''{0}'' reconciler named ''{1}'' for ''{2}'' resource (version ''{3}'')",
                    reconcilerClassName, name, resourceFullName, HasMetadata.getApiVersion(resourceClass));
        } else {
            log.infov("Skipped configuration reload for ''{0}'' reconciler as no changes were detected",
                    reconcilerClassName);

            // register the dependent beans so that they can be found during dev mode after a restart
            // where the dependents might not have been resolved yet
            if (configuration.needsDependentBeansCreation()) {
                log.debugv("Created dependent beans for ''{0}'' reconciler", reconcilerClassName);
                reconcilerInfo.getDependentResourceInfos().forEach(dependent -> additionalBeans.produce(
                        AdditionalBeanBuildItem.builder()
                                .addBeanClass(dependent.classInfo().name().toString())
                                .setUnremovable()
                                .setDefaultScope(APPLICATION_SCOPED)
                                .build()));
            }
        }

        // store the configuration in the live reload context
        if (storedConfigurations == null) {
            storedConfigurations = new ContextStoredControllerConfigurations();
        }
        storedConfigurations.recordConfiguration(configuration);
        liveReload.setContextObject(ContextStoredControllerConfigurations.class, storedConfigurations);

        return configuration;
    }

    private static Class<?> getConfigurationAnnotationClass(SelectiveAugmentedClassInfo configurationTargetInfo,
            AnnotationConfigurableAugmentedClassInfo configurableInfo) {
        if (configurableInfo != null) {
            final var associatedConfigurationClass = configurableInfo.getAssociatedConfigurationClass();
            if (configurationTargetInfo.classInfo().annotationsMap().containsKey(associatedConfigurationClass)) {
                return ClassLoadingUtils
                        .loadClass(associatedConfigurationClass.toString(), Object.class);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private DependentResourceSpecMetadata createDependentResourceSpec(
            DependentResourceAugmentedClassInfo dependent,
            IndexView index,
            QuarkusControllerConfiguration configuration) {
        final var dependentResourceType = dependent.classInfo();

        // resolve the associated resource type
        final var drTypeName = dependentResourceType.name();
        final var types = JandexUtil.resolveTypeParameters(drTypeName, Constants.DEPENDENT_RESOURCE,
                index);
        final String resourceTypeName;
        if (types.size() == 2) {
            resourceTypeName = types.get(0).name().toString();
        } else {
            throw new IllegalArgumentException(
                    "Improperly parameterized DependentResource implementation: " + drTypeName.toString());
        }

        final var dependentTypeName = drTypeName.toString();
        final var dependentClass = loadClass(dependentTypeName, DependentResource.class);

        final var cfg = DependentResourceConfigurationResolver.extractConfigurationFromConfigured(
                dependentClass, configuration);

        final var dependentConfig = dependent.getDependentAnnotationFromController();
        final var dependsOnField = dependentConfig.value("dependsOn");
        final var dependsOn = Optional.ofNullable(dependsOnField)
                .map(AnnotationValue::asStringArray)
                .filter(array -> array.length > 0)
                .map(Set::of).orElse(Collections.emptySet());

        final var readyCondition = ConfigurationUtils.instantiateImplementationClass(
                dependentConfig, "readyPostcondition", Condition.class,
                Condition.class, true, index);
        final var reconcilePrecondition = ConfigurationUtils.instantiateImplementationClass(
                dependentConfig, "reconcilePrecondition", Condition.class,
                Condition.class, true, index);
        final var deletePostcondition = ConfigurationUtils.instantiateImplementationClass(
                dependentConfig, "deletePostcondition", Condition.class,
                Condition.class, true, index);
        final var useEventSourceWithName = ConfigurationUtils.annotationValueOrDefault(
                dependentConfig, "useEventSourceWithName", AnnotationValue::asString,
                () -> null);

        return new DependentResourceSpecMetadata(dependentClass, cfg, dependent.nameOrFailIfUnset(),
                dependsOn, readyCondition, reconcilePrecondition, deletePostcondition, useEventSourceWithName,
                resourceTypeName);

    }

    private String getFinalizer(AnnotationInstance controllerAnnotation, String crdName) {
        return ConfigurationUtils.annotationValueOrDefault(controllerAnnotation,
                "finalizerName",
                AnnotationValue::asString,
                () -> ReconcilerUtils.getDefaultFinalizerName(crdName));
    }

    private String getLabelSelector(AnnotationInstance controllerAnnotation) {
        return ConfigurationUtils.annotationValueOrDefault(controllerAnnotation,
                "labelSelector",
                AnnotationValue::asString,
                () -> null);
    }
}
