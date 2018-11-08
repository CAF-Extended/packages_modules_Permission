/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.role.model;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.permissioncontroller.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provides access to all the {@link Role} definitions.
 */
public class Roles {

    private static final String LOG_TAG = Roles.class.getSimpleName();

    // STOPSHIP: Turn this off before we ship.
    private static final boolean DEBUG = true;

    private static final String TAG_ROLES = "roles";
    private static final String TAG_PERMISSION_SET = "permission-set";
    private static final String TAG_ROLE = "role";
    private static final String TAG_REQUIRED_COMPONENTS = "required-components";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_PROVIDER = "provider";
    private static final String TAG_RECEIVER = "receiver";
    private static final String TAG_SERVICE = "service";
    private static final String TAG_INTENT_FILTER = "intent-filter";
    private static final String TAG_ACTION = "action";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_DATA = "data";
    private static final String TAG_PERMISSIONS = "permissions";
    private static final String TAG_PERMISSION = "permission";
    private static final String TAG_APP_OPS = "app-ops";
    private static final String TAG_APP_OP = "app-op";
    private static final String TAG_PREFERRED_ACTIVITIES = "preferred-activites";
    private static final String TAG_PREFERRED_ACTIVITY = "preferred-activity";
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_EXCLUSIVE = "exclusive";
    private static final String ATTRIBUTE_PERMISSION = "permission";
    private static final String ATTRIBUTE_SCHEME = "scheme";
    private static final String ATTRIBUTE_MIME_TYPE = "mimeType";
    private static final String ATTRIBUTE_MODE = "mode";

    private static final String MODE_NAME_ALLOWED = "allowed";
    private static final String MODE_NAME_IGNORED = "ignored";
    private static final String MODE_NAME_ERRORED = "errored";
    private static final String MODE_NAME_DEFAULT = "default";
    private static final String MODE_NAME_FOREGROUND = "foreground";
    private static final ArrayMap<String, Integer> sModeNameToMode = new ArrayMap<>();
    static {
        sModeNameToMode.put(MODE_NAME_ALLOWED, AppOpsManager.MODE_ALLOWED);
        sModeNameToMode.put(MODE_NAME_IGNORED, AppOpsManager.MODE_IGNORED);
        sModeNameToMode.put(MODE_NAME_ERRORED, AppOpsManager.MODE_ERRORED);
        sModeNameToMode.put(MODE_NAME_DEFAULT, AppOpsManager.MODE_DEFAULT);
        sModeNameToMode.put(MODE_NAME_FOREGROUND, AppOpsManager.MODE_FOREGROUND);
    }

    @NonNull
    private static final Object sLock = new Object();

    @Nullable
    private static Map<String, Role> sRoles;

    private Roles() {}

    /**
     * Get the roles defined in {@code roles.xml}.
     *
     * @param context the {@code Context} used to read the XML resource
     *
     * @return a map from role name to {@link Role} instances.
     */
    @NonNull
    public static Map<String, Role> getRoles(@NonNull Context context) {
        synchronized (sLock) {
            if (sRoles == null) {
                sRoles = loadRoles(context);
            }
            return sRoles;
        }
    }

    @NonNull
    private static Map<String, Role> loadRoles(@NonNull Context context) {
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.roles)) {
            Pair<Map<String, PermissionSet>, Map<String, Role>> xml = parseXml(parser);
            if (xml == null) {
                return Collections.emptyMap();
            }
            Map<String, PermissionSet> permissionSets = xml.first;
            Map<String, Role> roles = xml.second;
            validateParseResult(permissionSets, roles, context);
            return roles;
        } catch (XmlPullParserException | IOException e) {
            throwOrLogMessage("Unable to parse roles.xml", e);
            return Collections.emptyMap();
        }
    }

    @Nullable
    private static Pair<Map<String, PermissionSet>, Map<String, Role>> parseXml(
            @NonNull XmlPullParser parser) throws IOException, XmlPullParserException {
        Pair<Map<String, PermissionSet>, Map<String, Role>> xml = null;

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals(TAG_ROLES)) {
                if (xml != null) {
                    throwOrLogMessage("Duplicate <roles>");
                    skipCurrentTag(parser);
                    continue;
                }
                xml = parseRoles(parser);
            } else {
                throwOrLogForUnknownTag(parser);
                skipCurrentTag(parser);
            }
        }

        if (xml == null) {
            throwOrLogMessage("Missing <roles>");
        }
        return xml;
    }

    @NonNull
    private static Pair<Map<String, PermissionSet>, Map<String, Role>> parseRoles(
            @NonNull XmlPullParser parser) throws IOException, XmlPullParserException {
        Map<String, PermissionSet> permissionSets = new ArrayMap<>();
        Map<String, Role> roles = new ArrayMap<>();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            switch (parser.getName()) {
                case TAG_PERMISSION_SET: {
                    PermissionSet permissionSet = parsePermissionSet(parser);
                    if (permissionSet == null) {
                        continue;
                    }
                    checkDuplicateElement(permissionSet.getName(), permissionSets.keySet(),
                            "permission set");
                    permissionSets.put(permissionSet.getName(), permissionSet);
                    break;
                }
                case TAG_ROLE: {
                    Role role = parseRole(parser, permissionSets);
                    if (role == null) {
                        continue;
                    }
                    checkDuplicateElement(role.getName(), roles.keySet(), "role");
                    roles.put(role.getName(), role);
                    break;
                }
                default:
                    throwOrLogForUnknownTag(parser);
                    skipCurrentTag(parser);
            }
        }

        return new Pair<>(permissionSets, roles);
    }

    @Nullable
    private static PermissionSet parsePermissionSet(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String name = requireAttributeValue(parser, ATTRIBUTE_NAME, TAG_PERMISSION_SET);
        if (name == null) {
            skipCurrentTag(parser);
            return null;
        }

        List<String> permissions = new ArrayList<>();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals(TAG_PERMISSION)) {
                String permission = requireAttributeValue(parser, ATTRIBUTE_NAME, TAG_PERMISSION);
                if (permission == null) {
                    continue;
                }
                checkDuplicateElement(permission, permissions, "permission");
                permissions.add(permission);
            } else {
                throwOrLogForUnknownTag(parser);
                skipCurrentTag(parser);
            }
        }

        return new PermissionSet(name, permissions);
    }

    @Nullable
    private static Role parseRole(@NonNull XmlPullParser parser,
            @NonNull Map<String, PermissionSet> permissionSets) throws IOException,
            XmlPullParserException {
        String name = requireAttributeValue(parser, ATTRIBUTE_NAME, TAG_ROLE);
        if (name == null) {
            skipCurrentTag(parser);
            return null;
        }

        String exclusiveString = requireAttributeValue(parser, ATTRIBUTE_EXCLUSIVE, TAG_ROLE);
        if (exclusiveString == null) {
            skipCurrentTag(parser);
            return null;
        }
        boolean exclusive;
        if (Objects.equals(exclusiveString, Boolean.toString(true))) {
            exclusive = true;
        } else if (Objects.equals(exclusiveString, Boolean.toString(false))) {
            exclusive = false;
        } else {
            throwOrLogMessage("Unknown value for \"exclusive\" on <role>: " + exclusiveString);
            skipCurrentTag(parser);
            return null;
        }

        List<RequiredComponent> requiredComponents = null;
        List<String> permissions = null;
        List<AppOp> appOps = null;
        List<PreferredActivity> preferredActivities = null;

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            switch (parser.getName()) {
                case TAG_REQUIRED_COMPONENTS:
                    if (requiredComponents != null) {
                        throwOrLogMessage("Duplicate <required-components> in role: " + name);
                        skipCurrentTag(parser);
                        continue;
                    }
                    requiredComponents = parseRequiredComponents(parser);
                    break;
                case TAG_PERMISSIONS:
                    if (permissions != null) {
                        throwOrLogMessage("Duplicate <permissions> in role: " + name);
                        skipCurrentTag(parser);
                        continue;
                    }
                    permissions = parsePermissions(parser, permissionSets);
                    break;
                case TAG_APP_OPS:
                    if (appOps != null) {
                        throwOrLogMessage("Duplicate <app-ops> in role: " + name);
                        skipCurrentTag(parser);
                        continue;
                    }
                    appOps = parseAppOps(parser);
                    break;
                case TAG_PREFERRED_ACTIVITIES:
                    if (preferredActivities != null) {
                        throwOrLogMessage("Duplicate <preferred-activities> in role: " + name);
                        skipCurrentTag(parser);
                        continue;
                    }
                    preferredActivities = parsePreferredActivities(parser);
                    break;
                default:
                    throwOrLogForUnknownTag(parser);
                    skipCurrentTag(parser);
            }
        }

        if (requiredComponents == null) {
            requiredComponents = Collections.emptyList();
        }
        if (permissions == null) {
            permissions = Collections.emptyList();
        }
        if (appOps == null) {
            appOps = Collections.emptyList();
        }
        if (preferredActivities == null) {
            preferredActivities = Collections.emptyList();
        }
        return new Role(name, exclusive, requiredComponents, permissions, appOps,
                preferredActivities);
    }

    @NonNull
    private static List<RequiredComponent> parseRequiredComponents(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        List<RequiredComponent> requiredComponents = new ArrayList<>();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String name = parser.getName();
            switch (name) {
                case TAG_ACTIVITY:
                case TAG_PROVIDER:
                case TAG_RECEIVER:
                case TAG_SERVICE: {
                    RequiredComponent requiredComponent = parseRequiredComponent(parser, name);
                    if (requiredComponent == null) {
                        continue;
                    }
                    checkDuplicateElement(requiredComponent, requiredComponents,
                            "require component");
                    requiredComponents.add(requiredComponent);
                    break;
                }
                default:
                    throwOrLogForUnknownTag(parser);
                    skipCurrentTag(parser);
            }
        }

        return requiredComponents;
    }

    @Nullable
    private static RequiredComponent parseRequiredComponent(@NonNull XmlPullParser parser,
            @NonNull String name) throws IOException, XmlPullParserException {
        String permission = getAttributeValue(parser, ATTRIBUTE_PERMISSION);
        IntentFilterData intentFilterData = null;

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals(TAG_INTENT_FILTER)) {
                if (intentFilterData != null) {
                    throwOrLogMessage("Duplicate <intent-filter> in <" + name + ">");
                    skipCurrentTag(parser);
                    continue;
                }
                intentFilterData = parseIntentFilterData(parser);
            } else {
                throwOrLogForUnknownTag(parser);
                skipCurrentTag(parser);
            }
        }

        if (intentFilterData == null) {
            throwOrLogMessage("Missing <intent-filter> in <" + name + ">");
            return null;
        }
        switch (name) {
            case TAG_ACTIVITY:
                return new RequiredActivity(permission, intentFilterData);
            case TAG_PROVIDER:
                return new RequiredContentProvider(permission, intentFilterData);
            case TAG_RECEIVER:
                return new RequiredBroadcastReceiver(permission, intentFilterData);
            case TAG_SERVICE:
                return new RequiredService(permission, intentFilterData);
            default:
                throwOrLogMessage("Unknown tag <" + name + ">");
                return null;
        }
    }

    @Nullable
    private static IntentFilterData parseIntentFilterData(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String action = null;
        List<String> categories = new ArrayList<>();
        boolean hasData = false;
        String dataScheme = null;
        String dataType = null;

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            switch (parser.getName()) {
                case TAG_ACTION:
                    if (action != null) {
                        throwOrLogMessage("Duplicate <action> in <intent-filter>");
                        skipCurrentTag(parser);
                        continue;
                    }
                    action = requireAttributeValue(parser, ATTRIBUTE_NAME, TAG_ACTION);
                    break;
                case TAG_CATEGORY: {
                    String category = requireAttributeValue(parser, ATTRIBUTE_NAME, TAG_CATEGORY);
                    if (category == null) {
                        continue;
                    }
                    validateIntentFilterCategory(category);
                    checkDuplicateElement(category, categories, "category");
                    categories.add(category);
                    break;
                }
                case TAG_DATA:
                    if (!hasData) {
                        hasData = true;
                    } else {
                        throwOrLogMessage("Duplicate <data> in <intent-filter>");
                        skipCurrentTag(parser);
                        continue;
                    }
                    dataScheme = getAttributeValue(parser, ATTRIBUTE_SCHEME);
                    dataType = getAttributeValue(parser, ATTRIBUTE_MIME_TYPE);
                    if (dataType != null) {
                        validateIntentFilterDataType(dataType);
                    }
                    break;
                default:
                    throwOrLogForUnknownTag(parser);
                    skipCurrentTag(parser);
            }
        }

        if (action == null) {
            throwOrLogMessage("Missing <action> in <intent-filter>");
            return null;
        }
        return new IntentFilterData(action, categories, dataScheme, dataType);
    }

    private static void validateIntentFilterCategory(@NonNull String category) {
        if (Objects.equals(category, Intent.CATEGORY_DEFAULT)) {
            throwOrLogMessage("<category> should not include " + Intent.CATEGORY_DEFAULT);
        }
    }

    /**
     * Validates the data type with the same logic in {@link
     * android.content.IntentFilter#addDataType(String)} to prevent the {@code
     * MalformedMimeTypeException}.
     */
    private static void validateIntentFilterDataType(@NonNull String type) {
        int slashIndex = type.indexOf('/');
        if (slashIndex <= 0 || type.length() < slashIndex + 2) {
            throwOrLogMessage("Invalid attribute \"mimeType\" value on <data>: " + type);
        }
    }

    @NonNull
    private static List<String> parsePermissions(@NonNull XmlPullParser parser,
            @NonNull Map<String, PermissionSet> permissionSets) throws IOException,
            XmlPullParserException {
        List<String> permissions = new ArrayList<>();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            switch (parser.getName()) {
                case TAG_PERMISSION_SET: {
                    String permissionSetName = requireAttributeValue(parser, ATTRIBUTE_NAME,
                            TAG_PERMISSION_SET);
                    if (permissionSetName == null) {
                        continue;
                    }
                    if (!permissionSets.containsKey(permissionSetName)) {
                        throwOrLogMessage("Unknown permission set:" + permissionSetName);
                        continue;
                    }
                    PermissionSet permissionSet = permissionSets.get(permissionSetName);
                    // We do allow intersection between permission sets.
                    permissions.addAll(permissionSet.getPermissions());
                    break;
                }
                case TAG_PERMISSION: {
                    String permission = requireAttributeValue(parser, ATTRIBUTE_NAME,
                            TAG_PERMISSION);
                    if (permission == null) {
                        continue;
                    }
                    checkDuplicateElement(permission, permissions, "permission");
                    permissions.add(permission);
                    break;
                }
                default:
                    throwOrLogForUnknownTag(parser);
                    skipCurrentTag(parser);
            }
        }

        return permissions;
    }

    @NonNull
    private static List<AppOp> parseAppOps(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        List<String> appOpNames = new ArrayList<>();
        List<AppOp> appOps = new ArrayList<>();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals(TAG_APP_OP)) {
                String name = requireAttributeValue(parser, ATTRIBUTE_NAME, TAG_APP_OP);
                if (name == null) {
                    continue;
                }
                validateAppOpName(name);
                checkDuplicateElement(name, appOpNames, "app op");
                appOpNames.add(name);
                String modeName = requireAttributeValue(parser, ATTRIBUTE_MODE, TAG_APP_OP);
                if (modeName == null) {
                    continue;
                }
                int modeIndex = sModeNameToMode.indexOfKey(modeName);
                if (modeIndex < 0) {
                    throwOrLogMessage("Unknown value for \"mode\" on <app-op>: " + modeName);
                    continue;
                }
                int mode = sModeNameToMode.valueAt(modeIndex);
                AppOp appOp = new AppOp(name, mode);
                appOps.add(appOp);
            } else {
                throwOrLogForUnknownTag(parser);
                skipCurrentTag(parser);
            }
        }

        return appOps;
    }

    private static void validateAppOpName(@NonNull String appOpName) {
        if (DEBUG) {
            // Throws IllegalArgumentException if unknown.
            AppOpsManager.opToPermission(appOpName);
        }
    }

    @NonNull
    private static List<PreferredActivity> parsePreferredActivities(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        List<PreferredActivity> preferredActivities = new ArrayList<>();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals(TAG_PREFERRED_ACTIVITY)) {
                PreferredActivity preferredActivity = parsePreferredActivity(parser);
                if (preferredActivity == null) {
                    continue;
                }
                checkDuplicateElement(preferredActivity, preferredActivities,
                        "preferred activity");
                preferredActivities.add(preferredActivity);
            } else {
                throwOrLogForUnknownTag(parser);
                skipCurrentTag(parser);
            }
        }

        return preferredActivities;
    }

    @Nullable
    private static PreferredActivity parsePreferredActivity(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        RequiredActivity activity = null;
        List<IntentFilterData> intentFilterDatas = new ArrayList<>();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            switch (parser.getName()) {
                case TAG_ACTIVITY:
                    if (activity != null) {
                        throwOrLogMessage("Duplicate <activity> in <preferred-activity>");
                        skipCurrentTag(parser);
                        continue;
                    }
                    activity = (RequiredActivity) parseRequiredComponent(parser, TAG_ACTIVITY);
                    break;
                case TAG_INTENT_FILTER:
                    IntentFilterData intentFilterData = parseIntentFilterData(parser);
                    if (intentFilterData == null) {
                        continue;
                    }
                    checkDuplicateElement(intentFilterData, intentFilterDatas,
                            "intent filter");
                    intentFilterDatas.add(intentFilterData);
                    break;
                default:
                    throwOrLogForUnknownTag(parser);
                    skipCurrentTag(parser);
            }
        }

        if (activity == null) {
            throwOrLogMessage("Missing <activity> in <preferred-activity>");
            return null;
        }
        if (intentFilterDatas.isEmpty()) {
            throwOrLogMessage("Missing <intent-filter> in <preferred-activity>");
            return null;
        }
        return new PreferredActivity(activity, intentFilterDatas);
    }

    private static void skipCurrentTag(@NonNull XmlPullParser parser) throws XmlPullParserException,
            IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            // Do nothing
        }
    }

    @Nullable
    private static String getAttributeValue(@NonNull XmlPullParser parser, @NonNull String name) {
        return parser.getAttributeValue(null, name);
    }

    @Nullable
    private static String requireAttributeValue(@NonNull XmlPullParser parser, @NonNull String name,
            @NonNull String tagName) {
        String value = getAttributeValue(parser, name);
        if (value == null) {
            throwOrLogMessage("Missing attribute \"" + name + "\" on <" + tagName + ">");
        }
        return value;
    }

    private static void throwOrLogMessage(String message) {
        if (DEBUG) {
            throw new IllegalArgumentException(message);
        } else {
            Log.wtf(LOG_TAG, message);
        }
    }

    private static void throwOrLogMessage(String message, Throwable cause) {
        if (DEBUG) {
            throw new IllegalArgumentException(message, cause);
        } else {
            Log.wtf(LOG_TAG, message, cause);
        }
    }

    private static void throwOrLogForUnknownTag(@NonNull XmlPullParser parser) {
        throwOrLogMessage("Unknown tag: " + parser.getName());
    }

    private static <T> void checkDuplicateElement(@NonNull T element,
            @NonNull Collection<T> collection, @NonNull String name) {
        if (DEBUG) {
            if (collection.contains(element)) {
                throw new IllegalArgumentException("Duplicate " + name + ": " + element);
            }
        }
    }

    /**
     * Validates the permission names with {@code PackageManager} and ensures that all app ops with
     * a permission in {@code AppOpsManager} have declared that permission in its role and ensures
     * that all preferred activities are listed in the required components.
     */
    private static void validateParseResult(@NonNull Map<String, PermissionSet> permissionSets,
            @NonNull Map<String, Role> roles, @NonNull Context context) {
        if (!DEBUG) {
            return;
        }

        for (PermissionSet permissionSet : permissionSets.values()) {
            permissionSet.getPermissions().forEach(permission -> validatePermission(permission,
                    context));
        }
        for (Role role : roles.values()) {
            role.getRequiredComponents().forEach(requiredComponent -> {
                String permission = requiredComponent.getPermission();
                if (permission != null) {
                    validatePermission(permission, context);
                }
            });
            role.getPermissions().forEach(permission -> validatePermission(permission, context));
            role.getAppOps().forEach(appOp -> {
                String permission = AppOpsManager.opToPermission(appOp.getName());
                if (permission != null) {
                    throw new IllegalArgumentException("App op has an associated permission: "
                            + appOp.getName());
                }
            });
            role.getPreferredActivities().forEach(preferredActivity -> {
                if (!role.getRequiredComponents().contains(preferredActivity.getActivity())) {
                    throw new IllegalArgumentException("<activity> of <preferred-activity> not"
                            + " required in <required-components>, role: " + role.getName()
                            + ", preferred activity: " + preferredActivity);
                }
            });
        }
    }

    private static void validatePermission(@NonNull String permission, @NonNull Context context) {
        try {
            context.getPackageManager().getPermissionInfo(permission, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown permission: " + permission, e);
        }
    }
}
