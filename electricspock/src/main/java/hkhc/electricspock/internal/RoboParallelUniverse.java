/*
 * Copyright 2016 Herman Cheung
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
 *
 */

package hkhc.electricspock.internal;

/**
 * Created by herman on 27/12/2016.
 */

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Looper;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.ShadowsAdapter;
import org.robolectric.TestLifecycle;
import org.robolectric.annotation.Config;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.internal.SdkConfig;
import org.robolectric.internal.fakes.RoboInstrumentation;
import org.robolectric.manifest.ActivityData;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.OverlayResourceLoader;
import org.robolectric.res.PackageResourceLoader;
import org.robolectric.res.Qualifiers;
import org.robolectric.res.ResName;
import org.robolectric.res.ResourceIndex;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.ResourcePath;
import org.robolectric.res.RoutingResourceLoader;
import org.robolectric.res.builder.DefaultPackageManager;
import org.robolectric.res.builder.RobolectricPackageManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ApplicationTestUtil;
import org.robolectric.util.Pair;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.Scheduler;

import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoboParallelUniverse implements ParallelUniverseInterface {
    private final ShadowsAdapter shadowsAdapter = Robolectric.getShadowsAdapter();

    private boolean loggingInitialized = false;
    private SdkConfig sdkConfig;

    public RoboParallelUniverse() {
    }

    @Override
    public void resetStaticState(Config config) {
        RuntimeEnvironment.setMainThread(Thread.currentThread());
        Robolectric.reset();

        if (!loggingInitialized) {
            shadowsAdapter.setupLogging();
            loggingInitialized = true;
        }
    }

    private static Map<Pair<AndroidManifest, SdkConfig>, ResourceLoader> resourceLoadersByManifestAndConfig = new HashMap<Pair<AndroidManifest, SdkConfig>, ResourceLoader>();

    public PackageResourceLoader createResourceLoader(ResourcePath resourcePath) {
        return new PackageResourceLoader(resourcePath);
    }

    protected ResourceLoader createAppResourceLoader(ResourceLoader systemResourceLoader, AndroidManifest appManifest) {
        List<PackageResourceLoader> appAndLibraryResourceLoaders = new ArrayList<PackageResourceLoader>();
        for (ResourcePath resourcePath : appManifest.getIncludedResourcePaths()) {
            appAndLibraryResourceLoaders.add(createResourceLoader(resourcePath));
        }
        OverlayResourceLoader overlayResourceLoader = new OverlayResourceLoader(appManifest.getPackageName(), appAndLibraryResourceLoaders);

        Map<String, ResourceLoader> resourceLoaders = new HashMap<String, ResourceLoader>();
        resourceLoaders.put("android", systemResourceLoader);
        resourceLoaders.put(appManifest.getPackageName(), overlayResourceLoader);
        return new RoutingResourceLoader(resourceLoaders);
    }

    public final ResourceLoader getAppResourceLoader(SdkConfig sdkConfig, ResourceLoader systemResourceLoader, final AndroidManifest appManifest) {
        Pair<AndroidManifest, SdkConfig> androidManifestSdkConfigPair = new Pair<AndroidManifest, SdkConfig>(appManifest, sdkConfig);
        ResourceLoader resourceLoader = resourceLoadersByManifestAndConfig.get(androidManifestSdkConfigPair);
        if (resourceLoader == null) {
            resourceLoader = createAppResourceLoader(systemResourceLoader, appManifest);
            resourceLoadersByManifestAndConfig.put(androidManifestSdkConfigPair, resourceLoader);
        }
        return resourceLoader;
    }

    @Override
    public void setUpApplicationState(Method method, TestLifecycle testLifecycle, ResourceLoader systemResourceLoader, AndroidManifest appManifest, Config config) {
        RuntimeEnvironment.application = null;
        RuntimeEnvironment.setMasterScheduler(new Scheduler());
        RuntimeEnvironment.setMainThread(Thread.currentThread());
        ResourceLoader appResourceLoader = getAppResourceLoader(sdkConfig, systemResourceLoader, appManifest);

        DefaultPackageManager packageManager = new DefaultPackageManager();
        initializeAppManifest(appManifest, appResourceLoader, packageManager);
        RuntimeEnvironment.setRobolectricPackageManager(packageManager);

        RuntimeEnvironment.setAppResourceLoader(appResourceLoader);
        RuntimeEnvironment.setSystemResourceLoader(systemResourceLoader);

        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
        }

        String qualifiers = Qualifiers.addPlatformVersion(config.qualifiers(), sdkConfig.getApiLevel());
        qualifiers = Qualifiers.addSmallestScreenWidth(qualifiers, 320);
        qualifiers = Qualifiers.addScreenWidth(qualifiers, 320);
        Resources systemResources = Resources.getSystem();
        Configuration configuration = systemResources.getConfiguration();
        configuration.smallestScreenWidthDp = Qualifiers.getSmallestScreenWidth(qualifiers);
        configuration.screenWidthDp = Qualifiers.getScreenWidth(qualifiers);
        shadowsAdapter.overrideQualifiers(configuration, qualifiers);
        systemResources.updateConfiguration(configuration, systemResources.getDisplayMetrics());
        RuntimeEnvironment.setQualifiers(qualifiers);
        RuntimeEnvironment.setApiLevel(sdkConfig.getApiLevel());

        Class<?> contextImplClass = ReflectionHelpers.loadClass(getClass().getClassLoader(), shadowsAdapter.getShadowContextImplClassName());

        Class<?> activityThreadClass = ReflectionHelpers.loadClass(getClass().getClassLoader(), shadowsAdapter.getShadowActivityThreadClassName());
        // Looper needs to be prepared before the activity thread is created
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        ShadowLooper.getShadowMainLooper().resetScheduler();
        Object activityThread = ReflectionHelpers.newInstance(activityThreadClass);
        RuntimeEnvironment.setActivityThread(activityThread);

        ReflectionHelpers.setField(activityThread, "mInstrumentation", new RoboInstrumentation());
        ReflectionHelpers.setField(activityThread, "mCompatConfiguration", configuration);

        Context systemContextImpl = ReflectionHelpers.callStaticMethod(contextImplClass, "createSystemContext", ReflectionHelpers.ClassParameter.from(activityThreadClass, activityThread));

        final Application application = (Application) testLifecycle.createApplication(method, appManifest, config);
        RuntimeEnvironment.application = application;

        if (application != null) {
            shadowsAdapter.bind(application, appManifest);

            ApplicationInfo applicationInfo;
            try {
                applicationInfo = RuntimeEnvironment.getPackageManager().getApplicationInfo(appManifest.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }

            Class<?> compatibilityInfoClass = ReflectionHelpers.loadClass(getClass().getClassLoader(), "android.content.res.CompatibilityInfo");

            Object loadedApk = ReflectionHelpers.callInstanceMethod(activityThread, "getPackageInfo",
                    ReflectionHelpers.ClassParameter.from(ApplicationInfo.class, applicationInfo),
                    ReflectionHelpers.ClassParameter.from(compatibilityInfoClass, null),
                    ReflectionHelpers.ClassParameter.from(int.class, Context.CONTEXT_INCLUDE_CODE));

            try {
                Context contextImpl = systemContextImpl.createPackageContext(applicationInfo.packageName, Context.CONTEXT_INCLUDE_CODE);
                ReflectionHelpers.setField(activityThreadClass, activityThread, "mInitialApplication", application);
                ApplicationTestUtil.attach(application, contextImpl);
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }

            addManifestActivitiesToPackageManager(appManifest, application);

            Resources appResources = application.getResources();
            ReflectionHelpers.setField(loadedApk, "mResources", appResources);
            ReflectionHelpers.setField(loadedApk, "mApplication", application);

            appResources.updateConfiguration(configuration, appResources.getDisplayMetrics());

            application.onCreate();
        }
    }

    private void initializeAppManifest(AndroidManifest appManifest, ResourceLoader appResourceLoader, DefaultPackageManager packageManager) {
        appManifest.initMetaData(appResourceLoader);
        ResourceIndex resourceIndex = appResourceLoader.getResourceIndex();

        int labelRes = 0;
        if (appManifest.getLabelRef() != null && resourceIndex != null) {
            Integer id = ResName.getResourceId(resourceIndex, appManifest.getLabelRef(), appManifest.getPackageName());
            labelRes = id != null ? id : 0;
        }
        packageManager.addManifest(appManifest, labelRes);
    }

    private void addManifestActivitiesToPackageManager(AndroidManifest appManifest, Application application) {
        if (appManifest != null) {
            Map<String,ActivityData> activityDatas = appManifest.getActivityDatas();

            RobolectricPackageManager packageManager = (RobolectricPackageManager) application.getPackageManager();

            for (ActivityData data : activityDatas.values()) {
                String name = data.getName();
                String activityName = name.startsWith(".") ? appManifest.getPackageName() + name : name;
                packageManager.addResolveInfoForIntent(new Intent(activityName), new ResolveInfo());
            }
        }
    }

    @Override
    public Thread getMainThread() {
        return RuntimeEnvironment.getMainThread();
    }

    @Override
    public void setMainThread(Thread newMainThread) {
        RuntimeEnvironment.setMainThread(newMainThread);
    }

    @Override
    public void tearDownApplication() {
        if (RuntimeEnvironment.application != null) {
            RuntimeEnvironment.application.onTerminate();
        }
    }

    @Override
    public Object getCurrentApplication() {
        return RuntimeEnvironment.application;
    }

    @Override
    public void setSdkConfig(SdkConfig sdkConfig) {
        this.sdkConfig = sdkConfig;
    }
}
