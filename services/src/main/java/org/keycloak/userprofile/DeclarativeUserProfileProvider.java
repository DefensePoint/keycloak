/*
 *
 *  * Copyright 2021  Red Hat, Inc. and/or its affiliates
 *  * and other contributors as indicated by the @author tags.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.keycloak.userprofile;

import static org.keycloak.common.util.ObjectUtil.isBlank;
import static org.keycloak.protocol.oidc.TokenManager.getRequestedClientScopes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.userprofile.config.DeclarativeUserProfileModel;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPAttributeRequired;
import org.keycloak.representations.userprofile.config.UPAttributeSelector;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.userprofile.config.UPConfigUtils;
import org.keycloak.representations.userprofile.config.UPGroup;
import org.keycloak.userprofile.validator.AttributeRequiredByMetadataValidator;
import org.keycloak.userprofile.validator.ImmutableAttributeValidator;
import org.keycloak.util.JsonSerialization;
import org.keycloak.validate.AbstractSimpleValidator;
import org.keycloak.validate.ValidatorConfig;

/**
 * {@link UserProfileProvider} loading configuration from the changeable JSON file stored in component config. Parsed
 * configuration is cached.
 *
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 * @author Vlastimil Elias <velias@redhat.com>
 */
public class DeclarativeUserProfileProvider implements UserProfileProvider {

    public static final String UP_COMPONENT_CONFIG_KEY = "kc.user.profile.config";
    protected static final String PARSED_CONFIG_COMPONENT_KEY = "kc.user.profile.metadata";
    protected static final String PARSED_UP_CONFIG_COMPONENT_KEY = "kc.parsed.up.config";

    /**
     * Method used for predicate which returns true if any of the configuredScopes is requested in current auth flow.
     * 
     * @param context to get current auth flow from
     * @param configuredScopes to be evaluated
     * @return
     */
    private static boolean requestedScopePredicate(AttributeContext context, Set<String> configuredScopes) {
        KeycloakSession session = context.getSession();
        AuthenticationSessionModel authenticationSession = session.getContext().getAuthenticationSession();

        if (authenticationSession == null) {
            return false;
        }

        String requestedScopesString = authenticationSession.getClientNote(OIDCLoginProtocol.SCOPE_PARAM);
        ClientModel client = authenticationSession.getClient();

        return getRequestedClientScopes(requestedScopesString, client).map((csm) -> csm.getName()).anyMatch(configuredScopes::contains);
    }

    private final KeycloakSession session;
    private final String providerId;
    private final Map<UserProfileContext, UserProfileMetadata> contextualMetadataRegistry;
    protected final UPConfig parsedDefaultRawConfig;

    public DeclarativeUserProfileProvider(KeycloakSession session, DeclarativeUserProfileProviderFactory factory) {
        this.session = session;
        this.providerId = factory.getId();
        this.contextualMetadataRegistry = factory.getContextualMetadataRegistry();
        this.parsedDefaultRawConfig = factory.getParsedDefaultRawConfig();
    }

    protected Attributes createAttributes(UserProfileContext context, Map<String, ?> attributes,
            UserModel user, UserProfileMetadata metadata) {

        if (user != null && user.getServiceAccountClientLink() != null) {
            return new LegacyAttributes(context, attributes, user, metadata, session);
        }
        return new DefaultAttributes(context, attributes, user, metadata, session);
    }

    @Override
    public UserProfile create(UserProfileContext context, UserModel user) {
        return createUserProfile(context, user.getAttributes(), user);
    }

    @Override
    public UserProfile create(UserProfileContext context, Map<String, ?> attributes, UserModel user) {
        return createUserProfile(context, attributes, user);
    }

    @Override
    public UserProfile create(UserProfileContext context, Map<String, ?> attributes) {
        return createUserProfile(context, attributes, null);
    }

    private UserProfile createUserProfile(UserProfileContext context, Map<String, ?> attributes, UserModel user) {
        UserProfileMetadata defaultMetadata = contextualMetadataRegistry.get(context);

        if (defaultMetadata == null) {
            // some contexts (and their metadata) are available enabled when the corresponding feature is enabled
            throw new RuntimeException("No metadata is bound to the " + context + " context");
        }

        UserProfileMetadata metadata = configureUserProfile(defaultMetadata, session);
        Attributes profileAttributes = createAttributes(context, attributes, user, metadata);
        return new DefaultUserProfile(metadata, profileAttributes, createUserFactory(), user, session);
    }

    /**
     * Creates a {@link Function} for creating new users when the creating them using {@link UserProfile#create()}.
     *
     * @return a function for creating new users.
     */
    private Function<Attributes, UserModel> createUserFactory() {
        return new Function<Attributes, UserModel>() {
            private UserModel user;

            @Override
            public UserModel apply(Attributes attributes) {
                if (user == null) {
                    String userName = attributes.getFirst(UserModel.USERNAME);

                    // fallback to email in case email is allowed
                    if (userName == null) {
                        userName = attributes.getFirst(UserModel.EMAIL);
                    }

                    user = session.users().addUser(session.getContext().getRealm(), userName);
                }

                return user;
            }
        };
    }

    /**
     * Specifies how contextual profile metadata is configured at runtime.
     *
     * @param metadata the profile metadata
     * @return the metadata
     */
    protected UserProfileMetadata configureUserProfile(UserProfileMetadata metadata, KeycloakSession session) {
        UserProfileContext context = metadata.getContext();
        UserProfileMetadata decoratedMetadata = metadata.clone();
        RealmModel realm = session.getContext().getRealm();

        ComponentModel component = getComponentModel().orElse(null);

        if (component == null) {
            // makes sure user providers can override metadata for any attribute
            decorateUserProfileMetadataWithUserStorage(realm, decoratedMetadata);
            return decoratedMetadata;
        }

        Map<UserProfileContext, UserProfileMetadata> metadataMap = component.getNote(PARSED_CONFIG_COMPONENT_KEY);

        // not cached, create a note with cache
        if (metadataMap == null) {
            metadataMap = new ConcurrentHashMap<>();
            component.setNote(PARSED_CONFIG_COMPONENT_KEY, metadataMap);
        }

        return metadataMap.computeIfAbsent(context, createUserDefinedProfileDecorator(session, decoratedMetadata, component));
    }

    @Override
    public UPConfig getConfiguration() {
        Optional<ComponentModel> component = getComponentModel();

        if (component.isPresent()) {
            UPConfig cfg = getConfigFromComponentModel(component.get());

            if (cfg == null) {
                cfg = parsedDefaultRawConfig;
            }

            return cfg.clone();
        }

        return parsedDefaultRawConfig.clone();
    }

    @Override
    public void setConfiguration(UPConfig configuration) {
        RealmModel realm = session.getContext().getRealm();
        Optional<ComponentModel> optionalComponent = realm.getComponentsStream(realm.getId(), UserProfileProvider.class.getName()).findAny();

        // Avoid creating componentModel and then removing it right away
        if (!optionalComponent.isPresent() && configuration == null) return;

        ComponentModel component = optionalComponent.isPresent() ? optionalComponent.get() : createComponentModel();

        removeConfigJsonFromComponentModel(component);

        if (configuration == null) {
            realm.removeComponent(component);
            return;
        }

        try {
            component.getConfig().putSingle(UP_COMPONENT_CONFIG_KEY, JsonSerialization.writeValueAsString(configuration));
        } catch (IOException ioe) {
            throw new RuntimeException("Cannot write component config", ioe);
        }

        realm.updateComponent(component);
    }

    private Optional<ComponentModel> getComponentModel() {
        RealmModel realm = session.getContext().getRealm();
        return realm.getComponentsStream(realm.getId(), UserProfileProvider.class.getName()).findAny();
    }

    /**
     * Decorate basic metadata based on 'per realm' configuration.
     * This method is called for each {@link UserProfileContext} in each realm, and metadata are cached then and this
     * method is called again only if configuration changes.
     */
    protected UserProfileMetadata decorateUserProfileForCache(UserProfileMetadata decoratedMetadata, UPConfig parsedConfig) {
        UserProfileContext context = decoratedMetadata.getContext();

        if (parsedConfig == null) {
            return decoratedMetadata;
        }

        Map<String, UPGroup> groupsByName = asHashMap(parsedConfig.getGroups());
        int guiOrder = 0;
        
        for (UPAttribute attrConfig : parsedConfig.getAttributes()) {
            String attributeName = attrConfig.getName();

            if (!context.isAttributeSupported(attributeName)) {
                // attributes not supported by the context are ignored
                // for instance, only support email attribute when at the UPDATE_EMAIL context
                continue;
            }

            List<AttributeValidatorMetadata> validators = new ArrayList<>();
            Map<String, Map<String, Object>> validationsConfig = attrConfig.getValidations();

            if (validationsConfig != null) {
                for (Map.Entry<String, Map<String, Object>> vc : validationsConfig.entrySet()) {
                    validators.add(createConfiguredValidator(vc.getKey(), vc.getValue()));
                }
            }

            UPAttributeRequired rc = attrConfig.getRequired();
            if (rc != null) {
                validators.add(new AttributeValidatorMetadata(AttributeRequiredByMetadataValidator.ID));
            }

            Predicate<AttributeContext> required = AttributeMetadata.ALWAYS_FALSE;
            if (rc != null) {
                if (rc.isAlways() || context.isRoleForContext(rc.getRoles())) {
                    required = AttributeMetadata.ALWAYS_TRUE;

                    // If scopes are configured, we will use scope-based selector and require the attribute just if scope is
                    // in current authenticationSession (either default scope or by 'scope' parameter)
                    if (rc.getScopes() != null && !rc.getScopes().isEmpty()) {
                        if (context.canBeAuthFlowContext()) {
                            required = (c) -> requestedScopePredicate(c, rc.getScopes());
                        } else {
                            // Scopes not available for admin and account contexts
                            required = AttributeMetadata.ALWAYS_FALSE;
                        }
                    }
                } else if (context.canBeAuthFlowContext() && rc.getScopes() != null && !rc.getScopes().isEmpty()) {
                    // for contexts executed from auth flow and with configured scopes requirement
                    // we have to create required validation with scopes based selector
                    required = (c) -> requestedScopePredicate(c, rc.getScopes());
                }
            }

            Predicate<AttributeContext> writeAllowed = AttributeMetadata.ALWAYS_FALSE;
            Predicate<AttributeContext> readAllowed = AttributeMetadata.ALWAYS_FALSE;
            UPAttributePermissions permissions = attrConfig.getPermissions();

            if (permissions != null) {
                Set<String> editRoles = permissions.getEdit();

                if (!editRoles.isEmpty()) {
                    writeAllowed = ac -> ac.getContext().isRoleForContext(editRoles);
                }

                Set<String> viewRoles = permissions.getView();

                if (viewRoles.isEmpty()) {
                    readAllowed = writeAllowed;
                } else {
                    readAllowed = createViewAllowedPredicate(writeAllowed, viewRoles);
                }
            }

            Predicate<AttributeContext> selector = AttributeMetadata.ALWAYS_TRUE;
            UPAttributeSelector sc = attrConfig.getSelector();
            if (sc != null && !isBuiltInAttribute(attributeName) && context.canBeAuthFlowContext() && sc.getScopes() != null && !sc.getScopes().isEmpty()) {
                // for contexts executed from auth flow and with configured scopes selector
                // we have to create correct predicate
                selector = (c) -> requestedScopePredicate(c, sc.getScopes());
            }

            Map<String, Object> annotations = attrConfig.getAnnotations();
            String attributeGroup = attrConfig.getGroup();
            AttributeGroupMetadata groupMetadata = toAttributeGroupMeta(groupsByName.get(attributeGroup));

            guiOrder++;

            validators.add(new AttributeValidatorMetadata(ImmutableAttributeValidator.ID));

            if (isBuiltInAttribute(attributeName)) {
                // make sure username and email are writable if permissions are not set
                if (permissions == null || permissions.isEmpty()) {
                    writeAllowed = AttributeMetadata.ALWAYS_TRUE;
                    readAllowed = AttributeMetadata.ALWAYS_TRUE;
                }

                if (UserModel.USERNAME.equals(attributeName)) {
                    required = new Predicate<AttributeContext>() {
                        @Override
                        public boolean test(AttributeContext context) {
                            RealmModel realm = context.getSession().getContext().getRealm();
                            return !realm.isRegistrationEmailAsUsername();
                        }
                    };
                }

                if (UserModel.EMAIL.equals(attributeName)) {
                    Predicate<AttributeContext> requiredFromConfig = required;
                    required = new Predicate<AttributeContext>() {
                        @Override
                        public boolean test(AttributeContext context) {
                            UserModel user = context.getUser();

                            if (user != null && user.getServiceAccountClientLink() != null) {
                                return false;
                            }

                            if (requiredFromConfig.test(context)) return true;

                            RealmModel realm = context.getSession().getContext().getRealm();
                            return realm.isRegistrationEmailAsUsername();
                        }
                    };
                }

                List<AttributeMetadata> existingMetadata = decoratedMetadata.getAttribute(attributeName);

                if (existingMetadata.isEmpty()) {
                    throw new IllegalStateException("Attribute " + attributeName + " not defined in the context.");
                }

                for (AttributeMetadata metadata : existingMetadata) {
                    metadata.addAnnotations(annotations)
                            .setAttributeDisplayName(attrConfig.getDisplayName())
                            .setGuiOrder(guiOrder)
                            .setAttributeGroupMetadata(groupMetadata)
                            .addReadCondition(readAllowed)
                            .addWriteCondition(writeAllowed)
                            .addValidators(validators)
                            .setRequired(required);
                }
            } else {
                decoratedMetadata.addAttribute(attributeName, guiOrder, validators, selector, writeAllowed, required, readAllowed)
                        .addAnnotations(annotations)
                        .setAttributeDisplayName(attrConfig.getDisplayName())
                        .setAttributeGroupMetadata(groupMetadata);
            }
        }

        if (session != null) {
            // makes sure user providers can override metadata for any attribute
            decorateUserProfileMetadataWithUserStorage(session.getContext().getRealm(), decoratedMetadata);
        }

        return decoratedMetadata;

    }

    private void decorateUserProfileMetadataWithUserStorage(RealmModel realm, UserProfileMetadata userProfileMetadata) {
        // makes sure user providers can override metadata for any attribute
        UserProvider users = session.users();
        if (users instanceof UserProfileDecorator) {
            ((UserProfileDecorator) users).decorateUserProfile(realm, userProfileMetadata);
        }
    }

    private Map<String, UPGroup> asHashMap(List<UPGroup> groups) {
        return groups.stream().collect(Collectors.toMap(g -> g.getName(), g -> g));
    }
    
    private AttributeGroupMetadata toAttributeGroupMeta(UPGroup group) {
        if (group == null) {
            return null;
        }
        return new AttributeGroupMetadata(group.getName(), group.getDisplayHeader(), group.getDisplayDescription(), group.getAnnotations());
    }

    private boolean isBuiltInAttribute(String attributeName) {
        return UserModel.USERNAME.equals(attributeName) || UserModel.EMAIL.equals(attributeName);
    }

    private boolean isOptionalBuiltInAttribute(String attributeName) {
        return UserModel.FIRST_NAME.equals(attributeName) || UserModel.LAST_NAME.equals(attributeName);
    }

    private Predicate<AttributeContext> createViewAllowedPredicate(Predicate<AttributeContext> canEdit,
            Set<String> viewRoles) {
        return ac -> ac.getContext().isRoleForContext(viewRoles) || canEdit.test(ac);
    }

    /**
     * Get parsed config file configured in model. Default one used if not configured.
     */
    protected UPConfig getParsedConfig(String rawConfig) {
        if (!isBlank(rawConfig)) {
            try {
                return UPConfigUtils.parseConfig(rawConfig);
            } catch (IOException e) {
                throw new RuntimeException("UserProfile configuration for realm '" + session.getContext().getRealm().getName() + "' is invalid:" + e.getMessage(), e);
            }
        }

        return null;
    }

    /**
     * Create the component model to store configuration
     * @return component model
     */
    protected ComponentModel createComponentModel() {
        RealmModel realm = session.getContext().getRealm();
        return realm.addComponentModel(new DeclarativeUserProfileModel(providerId));
    }

    /**
     * Create validator for validation configured in the user profile config.
     *
     * @param validator id to create validator for
     * @param validatorConfig of the validator
     * @return validator metadata to run given validation
     */
    protected AttributeValidatorMetadata createConfiguredValidator(String validator, Map<String, Object> validatorConfig) {
        return new AttributeValidatorMetadata(validator, ValidatorConfig.builder().config(validatorConfig).config(AbstractSimpleValidator.IGNORE_EMPTY_VALUE, true).build());
    }

    private UPConfig getConfigFromComponentModel(ComponentModel model) {
        if (model == null)
            return null;

        UPConfig cfg = model.getNote(PARSED_UP_CONFIG_COMPONENT_KEY);
        if (cfg != null) {
            return cfg;
        }

        String rawConfig = model.get(UP_COMPONENT_CONFIG_KEY);
        if (rawConfig == null) {
            return null;
        } else {
            cfg = getParsedConfig(rawConfig);
            model.setNote(PARSED_UP_CONFIG_COMPONENT_KEY, cfg);
            return cfg;
        }
    }

    private void removeConfigJsonFromComponentModel(ComponentModel model) {
        if (model == null)
            return;

        model.getConfig().remove(UP_COMPONENT_CONFIG_KEY);
    }

    @Override
    public void close() {
    }

    private Function<UserProfileContext, UserProfileMetadata> createUserDefinedProfileDecorator(KeycloakSession session, UserProfileMetadata decoratedMetadata, ComponentModel component) {
        return (c) -> {
            UPConfig parsedConfig = getConfigFromComponentModel(component);

            //validate configuration to catch things like changed/removed validators etc, and warn early and clearly about this problem
            List<String> errors = UPConfigUtils.validate(session, parsedConfig);
            if (!errors.isEmpty()) {
                throw new RuntimeException("UserProfile configuration for realm '" + session.getContext().getRealm().getName() + "' is invalid: " + errors.toString());
            }

            Iterator<AttributeMetadata> attributes = decoratedMetadata.getAttributes().iterator();

            while (attributes.hasNext()) {
                AttributeMetadata metadata = attributes.next();

                String attributeName = metadata.getName();

                if (isBuiltInAttribute(attributeName)) {
                    UPAttribute upAttribute = parsedDefaultRawConfig.getAttribute(attributeName);
                    Map<String, Map<String, Object>> validations = Optional.ofNullable(upAttribute.getValidations()).orElse(Collections.emptyMap());

                    for (String id : validations.keySet()) {
                        List<AttributeValidatorMetadata> validators = metadata.getValidators();
                        // do not include the default validators for built-in attributes into the base metadata
                        // user-defined configuration will add its own validators
                        validators.removeIf(m -> m.getValidatorId().equals(id));
                    }
                } else if (isOptionalBuiltInAttribute(attributeName)) {
                    // removes optional default attributes in favor of user-defined configuration
                    // make sure any attribute other than username and email are removed from the metadata
                    attributes.remove();
                }
            }

            return decorateUserProfileForCache(decoratedMetadata, parsedConfig);
        };
    }
}
