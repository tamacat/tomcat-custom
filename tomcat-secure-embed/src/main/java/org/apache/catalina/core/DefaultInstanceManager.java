/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.core;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.naming.Context;
import javax.naming.NamingException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.apache.catalina.ContainerServlet;
import org.apache.catalina.util.Introspection;
import org.apache.juli.logging.Log;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.ManagedConcurrentWeakHashMap;
import org.apache.tomcat.util.res.StringManager;

/**
 * Default implementation of {@link InstanceManager} that handles the {@code @PostConstruct} / {@code @PreDestroy}
 * lifecycle callbacks (annotations or deployment descriptor). Hardened build: JNDI has been removed so there is no
 * resource injection, and the lifecycle callbacks are always invoked.
 */
public class DefaultInstanceManager implements InstanceManager {

    // Used when there are no annotations in a class
    private static final AnnotationCacheEntry[] ANNOTATIONS_EMPTY = new AnnotationCacheEntry[0];

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(DefaultInstanceManager.class);

    /**
     * The class loader for loading application classes.
     */
    protected final ClassLoader classLoader;

    /**
     * The class loader for loading container classes.
     */
    protected final ClassLoader containerClassLoader;

    /**
     * Whether to perform privileged operations.
     */
    protected final boolean privileged;

    /**
     * Whether to ignore annotations during injection.
     */
    protected final boolean ignoreAnnotations;

    /**
     * Whether metadata is complete (no annotation scanning required).
     */
    protected final boolean metadataComplete;
    private final Set<String> restrictedClasses;
    private final ManagedConcurrentWeakHashMap<Class<?>,AnnotationCacheEntry[]> annotationCache =
            new ManagedConcurrentWeakHashMap<>();
    private final Map<String,String> postConstructMethods;
    private final Map<String,String> preDestroyMethods;

    /**
     * Create a new DefaultInstanceManager.
     *
     * @param catalinaContext The Catalina context for configuration
     * @param containerClassLoader The container class loader
     */
    public DefaultInstanceManager(org.apache.catalina.Context catalinaContext, ClassLoader containerClassLoader) {
        classLoader = catalinaContext.getLoader().getClassLoader();
        privileged = catalinaContext.getPrivileged();
        this.containerClassLoader = containerClassLoader;
        ignoreAnnotations = catalinaContext.getIgnoreAnnotations();
        metadataComplete = catalinaContext.getMetadataComplete();
        Log log = catalinaContext.getLogger();
        Set<String> classNames = new HashSet<>();
        loadProperties(classNames, "org/apache/catalina/core/RestrictedServlets.properties",
                "defaultInstanceManager.restrictedServletsResource", log);
        loadProperties(classNames, "org/apache/catalina/core/RestrictedListeners.properties",
                "defaultInstanceManager.restrictedListenersResource", log);
        loadProperties(classNames, "org/apache/catalina/core/RestrictedFilters.properties",
                "defaultInstanceManager.restrictedFiltersResource", log);
        restrictedClasses = Collections.unmodifiableSet(classNames);
        this.postConstructMethods = catalinaContext.findPostConstructMethods();
        this.preDestroyMethods = catalinaContext.findPreDestroyMethods();
    }

    @Override
    public Object newInstance(Class<?> clazz) throws IllegalAccessException, InvocationTargetException, NamingException,
            InstantiationException, IllegalArgumentException, NoSuchMethodException, SecurityException {
        return newInstance(clazz.getConstructor().newInstance(), clazz);
    }

    @Override
    public Object newInstance(String className)
            throws IllegalAccessException, InvocationTargetException, NamingException, InstantiationException,
            ClassNotFoundException, IllegalArgumentException, NoSuchMethodException, SecurityException {
        Class<?> clazz = loadClassMaybePrivileged(className, classLoader);
        return newInstance(clazz.getConstructor().newInstance(), clazz);
    }

    @Override
    public Object newInstance(final String className, final ClassLoader classLoader)
            throws IllegalAccessException, NamingException, InvocationTargetException, InstantiationException,
            ClassNotFoundException, IllegalArgumentException, NoSuchMethodException, SecurityException {
        Class<?> clazz = classLoader.loadClass(className);
        return newInstance(clazz.getConstructor().newInstance(), clazz);
    }

    @Override
    public void newInstance(Object o) throws IllegalAccessException, InvocationTargetException, NamingException {
        newInstance(o, o.getClass());
    }

    private Object newInstance(Object instance, Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException, NamingException {
        if (!ignoreAnnotations) {
            populateAnnotationsCache(clazz);
            postConstruct(instance, clazz);
        }
        return instance;
    }

    @Override
    public void destroyInstance(Object instance) throws IllegalAccessException, InvocationTargetException {
        if (!ignoreAnnotations) {
            preDestroy(instance, instance.getClass());
        }
    }

    /**
     * Call postConstruct method on the specified instance recursively from the deepest superclass to actual class.
     *
     * @param instance object to call postconstruct methods on
     * @param clazz    (super) class to examine for postConstruct annotation.
     *
     * @throws IllegalAccessException                      if postConstruct method is inaccessible.
     * @throws java.lang.reflect.InvocationTargetException if call fails
     */
    protected void postConstruct(Object instance, final Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != Object.class) {
            postConstruct(instance, superClass);
        }

        // At the end the postconstruct annotated
        // method is invoked
        AnnotationCacheEntry[] annotations = annotationCache.get(clazz);
        for (AnnotationCacheEntry entry : annotations) {
            if (entry.type() == AnnotationCacheEntryType.POST_CONSTRUCT) {
                // This will always return a new Method instance
                // Making this instance accessible does not affect other instances
                Method postConstruct = getMethod(clazz, entry);
                // If this doesn't work, just let invoke() fail
                postConstruct.trySetAccessible();
                postConstruct.invoke(instance);
            }
        }
    }


    /**
     * Call preDestroy method on the specified instance recursively from the deepest superclass to actual class.
     *
     * @param instance object to call preDestroy methods on
     * @param clazz    (super) class to examine for preDestroy annotation.
     *
     * @throws IllegalAccessException                      if preDestroy method is inaccessible.
     * @throws java.lang.reflect.InvocationTargetException if call fails
     */
    protected void preDestroy(Object instance, final Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != Object.class) {
            preDestroy(instance, superClass);
        }

        // At the end the predestroy annotated
        // method is invoked
        AnnotationCacheEntry[] annotations = annotationCache.get(clazz);
        if (annotations == null) {
            // instance not created through the instance manager
            return;
        }
        for (AnnotationCacheEntry entry : annotations) {
            if (entry.type() == AnnotationCacheEntryType.PRE_DESTROY) {
                // This will always return a new Method instance
                // Making this instance accessible does not affect other instances
                Method preDestroy = getMethod(clazz, entry);
                // If this doesn't work, just let invoke() fail
                preDestroy.trySetAccessible();
                preDestroy.invoke(instance);
            }
        }
    }


    @Override
    public void backgroundProcess() {
        annotationCache.maintain();
    }


    /**
     * Make sure that the annotations cache has been populated for the provided class.
     *
     * @param clazz      clazz to populate annotations for
     */
    protected void populateAnnotationsCache(Class<?> clazz) {

        List<AnnotationCacheEntry> annotations = null;

        while (clazz != null) {
            AnnotationCacheEntry[] annotationsArray = annotationCache.get(clazz);
            if (annotationsArray == null) {
                if (annotations == null) {
                    annotations = new ArrayList<>();
                } else {
                    annotations.clear();
                }

                // Initialize methods annotations
                Method[] methods = clazz.getDeclaredMethods();
                Method postConstruct = null;
                String postConstructFromXml = postConstructMethods.get(clazz.getName());
                Method preDestroy = null;
                String preDestroyFromXml = preDestroyMethods.get(clazz.getName());
                for (Method method : methods) {

                    postConstruct = findPostConstruct(postConstruct, postConstructFromXml, method);

                    preDestroy = findPreDestroy(preDestroy, preDestroyFromXml, method);
                }

                if (postConstruct != null) {
                    annotations.add(new AnnotationCacheEntry(postConstruct.getName(), postConstruct.getParameterTypes(),
                            null, AnnotationCacheEntryType.POST_CONSTRUCT));
                } else if (postConstructFromXml != null) {
                    throw new IllegalArgumentException(sm.getString("defaultInstanceManager.postConstructNotFound",
                            postConstructFromXml, clazz.getName()));
                }
                if (preDestroy != null) {
                    annotations.add(new AnnotationCacheEntry(preDestroy.getName(), preDestroy.getParameterTypes(), null,
                            AnnotationCacheEntryType.PRE_DESTROY));
                } else if (preDestroyFromXml != null) {
                    throw new IllegalArgumentException(sm.getString("defaultInstanceManager.preDestroyNotFound",
                            preDestroyFromXml, clazz.getName()));
                }


                if (annotations.isEmpty()) {
                    // Use common object to save memory
                    annotationsArray = ANNOTATIONS_EMPTY;
                } else {
                    annotationsArray = annotations.toArray(new AnnotationCacheEntry[0]);
                }
                synchronized (annotationCache) {
                    annotationCache.put(clazz, annotationsArray);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }


    /**
     * Makes cache size available to unit tests.
     *
     * @return the cache size
     */
    protected int getAnnotationCacheSize() {
        return annotationCache.size();
    }


    /**
     * Load a class, performing a privileged check if necessary.
     *
     * @param className The name of the class to load
     * @param classLoader The class loader to use
     * @return the loaded class
     * @throws ClassNotFoundException if the class cannot be found
     */
    protected Class<?> loadClassMaybePrivileged(final String className, final ClassLoader classLoader)
            throws ClassNotFoundException {
        Class<?> clazz = loadClass(className, classLoader);
        checkAccess(clazz);
        return clazz;
    }

    /**
     * Load a class using the appropriate class loader.
     *
     * @param className The name of the class to load
     * @param classLoader The class loader to use
     * @return the loaded class
     * @throws ClassNotFoundException if the class cannot be found
     */
    protected Class<?> loadClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        if (className.startsWith("org.apache.catalina")) {
            return containerClassLoader.loadClass(className);
        }
        try {
            Class<?> clazz = containerClassLoader.loadClass(className);
            if (ContainerServlet.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
        }
        return classLoader.loadClass(className);
    }

    private void checkAccess(Class<?> clazz) {
        if (privileged) {
            return;
        }
        if (ContainerServlet.class.isAssignableFrom(clazz)) {
            throw new SecurityException(sm.getString("defaultInstanceManager.restrictedContainerServlet", clazz));
        }
        while (clazz != null) {
            if (restrictedClasses.contains(clazz.getName())) {
                throw new SecurityException(sm.getString("defaultInstanceManager.restrictedClass", clazz));
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static void loadProperties(Set<String> classNames, String resourceName, String messageKey, Log log) {
        Properties properties = new Properties();
        ClassLoader cl = DefaultInstanceManager.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourceName)) {
            if (is == null) {
                log.error(sm.getString(messageKey, resourceName));
            } else {
                properties.load(is);
            }
        } catch (IOException ioe) {
            log.error(sm.getString(messageKey, resourceName), ioe);
        }
        if (properties.isEmpty()) {
            return;
        }
        for (Map.Entry<Object,Object> e : properties.entrySet()) {
            if ("restricted".equals(e.getValue())) {
                classNames.add(e.getKey().toString());
            } else {
                log.warn(sm.getString("defaultInstanceManager.restrictedWrongValue", resourceName, e.getKey(),
                        e.getValue()));
            }
        }
    }

    private static String normalize(String jndiName) {
        if (jndiName != null && jndiName.startsWith("java:comp/env/")) {
            return jndiName.substring(14);
        }
        return jndiName;
    }

    private static Method getMethod(final Class<?> clazz, final AnnotationCacheEntry entry) {
        Method result = null;
        try {
            result = clazz.getDeclaredMethod(entry.accessibleObjectName(), entry.paramTypes());
        } catch (NoSuchMethodException e) {
            // Should never happen. On that basis don't log it.
        }
        return result;
    }


    private Method findPostConstruct(Method currentPostConstruct, String postConstructFromXml, Method method) {
        return findLifecycleCallback(currentPostConstruct, postConstructFromXml, method, PostConstruct.class);
    }

    private Method findPreDestroy(Method currentPreDestroy, String preDestroyFromXml, Method method) {
        return findLifecycleCallback(currentPreDestroy, preDestroyFromXml, method, PreDestroy.class);
    }

    private Method findLifecycleCallback(Method currentMethod, String methodNameFromXml, Method method,
            Class<? extends Annotation> annotation) {
        Method result = currentMethod;
        if (methodNameFromXml != null) {
            if (method.getName().equals(methodNameFromXml)) {
                if (!Introspection.isValidLifecycleCallback(method)) {
                    throw new IllegalArgumentException(
                            sm.getString("defaultInstanceManager.invalidAnnotation", annotation.getName()));
                }
                result = method;
            }
        } else if (!metadataComplete) {
            if (method.isAnnotationPresent(annotation)) {
                if (currentMethod != null || !Introspection.isValidLifecycleCallback(method)) {
                    throw new IllegalArgumentException(
                            sm.getString("defaultInstanceManager.invalidAnnotation", annotation.getName()));
                }
                result = method;
            }
        }
        return result;
    }

    private record AnnotationCacheEntry(String accessibleObjectName, Class<?>[] paramTypes, String name,
            AnnotationCacheEntryType type) {
    }


    private enum AnnotationCacheEntryType {
        POST_CONSTRUCT,
        PRE_DESTROY
    }
}
