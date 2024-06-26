package jmri;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jmri.util.ThreadingUtil;

/**
 * Provides methods for locating various interface implementations. These form
 * the base for locating JMRI objects, including the key managers.
 * <p>
 * The structural goal is to have the jmri package not depend on lower
 * packages, with the implementations still available
 * at run-time through the InstanceManager.
 * <p>
 * To retrieve the default object of a specific type, do
 * {@link InstanceManager#getDefault} where the argument is e.g.
 * "SensorManager.class". In other words, you ask for the default object of a
 * particular type. Note that this call is intended to be used in the usual case
 * of requiring the object to function; it will log a message if there isn't
 * such an object. If that's routine, then use the
 * {@link InstanceManager#getNullableDefault} method instead.
 * <p>
 * Multiple items can be held, and are retrieved as a list with
 * {@link InstanceManager#getList}.
 * <p>
 * If a specific item is needed, e.g. one that has been constructed via a
 * complex process during startup, it should be installed with
 * {@link InstanceManager#store}.
 * <p>
 * If it is desirable for the InstanceManager to create an object on first
 * request, have that object's class implement the
 * {@link InstanceManagerAutoDefault} flag interface. The InstanceManager will
 * then construct a default object via the no-argument constructor when one is
 * first requested.
 * <p>
 * For initialization of more complex default objects, see the
 * {@link InstanceInitializer} mechanism and its default implementation in
 * {@link jmri.managers.DefaultInstanceInitializer}.
 * <p>
 * Implement the {@link InstanceManagerAutoInitialize} interface when default
 * objects need to be initialized after the default instance has been
 * constructed and registered with the InstanceManager. This will allow
 * references to the default instance during initialization to work as expected.
 * <hr>
 * This file is part of JMRI.
 * <p>
 * JMRI is free software; you can redistribute it and/or modify it under the
 * terms of version 2 of the GNU General Public License as published by the Free
 * Software Foundation. See the "COPYING" file for a copy of this license.
 * <p>
 * JMRI is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * @author Bob Jacobsen Copyright (C) 2001, 2008, 2013, 2016
 * @author Matthew Harris copyright (c) 2009
 */
public final class InstanceManager {

    // data members to hold contact with the property listeners
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final Map<Class<?>, List<Object>> managerLists = Collections.synchronizedMap(new HashMap<>());
    private final HashMap<Class<?>, InstanceInitializer> initializers = new HashMap<>();
    private final HashMap<Class<?>, StateHolder> initState = new HashMap<>();

    /**
     * Store an object of a particular type for later retrieval via
     * {@link #getDefault} or {@link #getList}.
     *
     * @param <T>  The type of the class
     * @param item The object of type T to be stored
     * @param type The class Object for the item's type. This will be used as
     *             the key to retrieve the object later.
     */
    public static <T> void store(@Nonnull T item, @Nonnull Class<T> type) {
        log.debug("Store item of type {}", type.getName());
        if (item == null) {
            NullPointerException npe = new NullPointerException();
            log.error("Should not store null value of type {}", type.getName());
            throw npe;
        }
        List<T> l = getList(type);
        l.add(item);
        getDefault().pcs.fireIndexedPropertyChange(getListPropertyName(type), l.indexOf(item), null, item);
    }


    /**
     * Store an object of a particular type for later retrieval via
     * {@link #getDefault} or {@link #getList}.
     *<p>
     * {@link #store} is preferred to this method because it does type
     * checking at compile time.  In (rare) cases that's not possible,
     * and run-time checking is required.
     *
     * @param <T>  The type of the class
     * @param item The object of type T to be stored
     * @param type The class Object for the item's type. This will be used as
     *             the key to retrieve the object later.
     */
    public static <T> void storeUnchecked(@Nonnull Object item, @Nonnull Class<T> type) {
        log.debug("Store item of type {}", type.getName());
        if (item == null) {
            NullPointerException npe = new NullPointerException();
            log.error("Should not store null value of type {}", type.getName());
            throw npe;
        }
        List<T> l = getList(type);
        try {
            l.add(type.cast(item));
            getDefault().pcs.fireIndexedPropertyChange(getListPropertyName(type), l.indexOf(item), null, item);
        } catch (ClassCastException ex) {
            log.error("Attempt to do unchecked store with invalid type {}", type, ex);
        }
    }

    /**
     * Retrieve a list of all objects of type T that were registered with
     * {@link #store}.
     *
     * @param <T>  The type of the class
     * @param type The class Object for the items' type.
     * @return A list of type Objects registered with the manager or an empty
     *         list.
     */
    @Nonnull
    public static <T> List<T> getList(@Nonnull Class<T> type) {
        return getDefault().getInstances(type);
    }

    /**
     * Retrieve a list of all objects of a specific type that were registered with
     * {@link #store}.
     *
     * Intended for use with i.e. scripts where access to the class type is inconvenient.
     * In Java code where typing is enforced, use {@link #getList(Class)}.
     *
     * @param className Fully qualified class name
     * @return A list of type Objects registered with the manager or an empty
     *         list.
     * @throws IllegalArgumentException if the named class doesn't exist
     */
    @Nonnull
    public static List<Object> getList(@Nonnull String className) {
        return getDefault().getInstances(className);
    }


    /**
     * Deregister all objects of a particular type.
     *
     * @param <T>  The type of the class
     * @param type The class Object for the items to be removed.
     */
    public static <T> void reset(@Nonnull Class<T> type) {
        getDefault().clear(type);
    }

    /**
     * Remove an object of a particular type that had earlier been registered
     * with {@link #store}. If item was previously registered, this will remove
     * item and fire an indexed property change event for the property matching
     * the output of {@link #getListPropertyName(java.lang.Class)} for type.
     * <p>
     * This is the static access to
     * {@link #remove(java.lang.Object, java.lang.Class)}.
     *
     * @param <T>  The type of the class
     * @param item The object of type T to be deregistered
     * @param type The class Object for the item's type
     */
    public static <T> void deregister(@Nonnull T item, @Nonnull Class<T> type) {
        getDefault().remove(item, type);
    }

    /**
     * Remove an object of a particular type that had earlier been registered
     * with {@link #store}. If item was previously registered, this will remove
     * item and fire an indexed property change event for the property matching
     * the output of {@link #getListPropertyName(java.lang.Class)} for type.
     *
     * @param <T>  The type of the class
     * @param item The object of type T to be deregistered
     * @param type The class Object for the item's type
     */
    public <T> void remove(@Nonnull T item, @Nonnull Class<T> type) {
        log.debug("Remove item type {}", type.getName());
        List<T> l = getList(type);
        int index = l.indexOf(item);
        if (index != -1) { // -1 means items was not in list, and therefore, not registered
            l.remove(item);
            if (item instanceof Disposable) {
                dispose((Disposable) item);
            }
        }
        // if removing last item, re-initialize later
        if (l.isEmpty()) {
            setInitializationState(type, InitializationState.NOTSET);
        }
        if (index != -1) { // -1 means items was not in list, and therefore, not registered
            // fire property change last
            pcs.fireIndexedPropertyChange(getListPropertyName(type), index, item, null);
        }
    }

    /**
     * Retrieve the last object of type T that was registered with
     * {@link #store(java.lang.Object, java.lang.Class) }.
     * <p>
     * Unless specifically set, the default is the last object stored, see the
     * {@link #setDefault(java.lang.Class, java.lang.Object) } method.
     * <p>
     * In some cases, InstanceManager can create the object the first time it's
     * requested. For more on that, see the class comment.
     * <p>
     * In most cases, system configuration assures the existence of a default
     * object, so this method will log and throw an exception if one doesn't
     * exist. Use {@link #getNullableDefault(java.lang.Class)} or
     * {@link #getOptionalDefault(java.lang.Class)} if the default is not
     * guaranteed to exist.
     *
     * @param <T>  The type of the class
     * @param type The class Object for the item's type
     * @return The default object for type
     * @throws NullPointerException if no default object for type exists
     * @see #getNullableDefault(java.lang.Class)
     * @see #getOptionalDefault(java.lang.Class)
     */
    @Nonnull
    public static <T> T getDefault(@Nonnull Class<T> type) {
        log.trace("getDefault of type {}", type.getName());
        T object = InstanceManager.getNullableDefault(type);
        if (object == null) {
            throw new NullPointerException("Required nonnull default for " + type.getName() + " does not exist.");
        }
        return object;
    }

    /**
     * Retrieve the last object of specific type that was registered with
     * {@link #store(java.lang.Object, java.lang.Class) }.
     *
     * Intended for use with i.e. scripts where access to the class type is inconvenient.
     * In Java code where typing is enforced, use {@link #getDefault(Class)}.
     *
     * <p>
     * Unless specifically set, the default is the last object stored, see the
     * {@link #setDefault(java.lang.Class, java.lang.Object) } method.
     * <p>
     * In some cases, InstanceManager can create the object the first time it's
     * requested. For more on that, see the class comment.
     * <p>
     * In most cases, system configuration assures the existence of a default
     * object, so this method will log and throw an exception if one doesn't
     * exist. Use {@link #getNullableDefault(java.lang.Class)} or
     * {@link #getOptionalDefault(java.lang.Class)} if the default is not
     * guaranteed to exist.
     *
     * @param className Fully qualified class name
     * @return The default object for type
     * @throws NullPointerException if no default object for type exists
     * @throws IllegalArgumentException if the named class doesn't exist
     * @see #getNullableDefault(java.lang.Class)
     * @see #getOptionalDefault(java.lang.Class)
     */
    @Nonnull
    public static Object getDefault(@Nonnull String className) {
        log.trace("getDefault of type {}", className);
        Object object = InstanceManager.getNullableDefault(className);
        if (object == null) {
            throw new NullPointerException("Required nonnull default for " + className + " does not exist.");
        }
        return object;
    }

    /**
     * Retrieve the last object of type T that was registered with
     * {@link #store(java.lang.Object, java.lang.Class) }.
     * <p>
     * Unless specifically set, the default is the last object stored, see the
     * {@link #setDefault(java.lang.Class, java.lang.Object) } method.
     * <p>
     * In some cases, InstanceManager can create the object the first time it's
     * requested. For more on that, see the class comment.
     * <p>
     * In most cases, system configuration assures the existence of a default
     * object, but this method also handles the case where one doesn't exist.
     * Use {@link #getDefault(java.lang.Class)} when the object is guaranteed to
     * exist.
     *
     * @param <T>  The type of the class
     * @param type The class Object for the item's type.
     * @return The default object for type.
     * @see #getOptionalDefault(java.lang.Class)
     */
    @CheckForNull
    public static <T> T getNullableDefault(@Nonnull Class<T> type) {
        return getDefault().getInstance(type);
    }

    /**
     * Retrieve the last object of type T that was registered with
     * {@link #store(java.lang.Object, java.lang.Class) }.
     *
     * Intended for use with i.e. scripts where access to the class type is inconvenient.
     * In Java code where typing is enforced, use {@link #getNullableDefault(Class)}.
     * <p>
     * Unless specifically set, the default is the last object stored, see the
     * {@link #setDefault(java.lang.Class, java.lang.Object) } method.
     * <p>
     * In some cases, InstanceManager can create the object the first time it's
     * requested. For more on that, see the class comment.
     * <p>
     * In most cases, system configuration assures the existence of a default
     * object, but this method also handles the case where one doesn't exist.
     * Use {@link #getDefault(java.lang.Class)} when the object is guaranteed to
     * exist.
     *
     * @param className Fully qualified class name
     * @return The default object for type.
     * @throws IllegalArgumentException if the named class doesn't exist
     * @see #getOptionalDefault(java.lang.Class)
     */
    @CheckForNull
    public static Object getNullableDefault(@Nonnull String className) {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException ex) {
            log.error("No class found: {}", className);
            throw new IllegalArgumentException(ex);
        }
        return getDefault().getInstance(type);
    }

    /**
     * Retrieve the last object of type T that was registered with
     * {@link #store(java.lang.Object, java.lang.Class) }.
     * <p>
     * Unless specifically set, the default is the last object stored, see the
     * {@link #setDefault(java.lang.Class, java.lang.Object) } method.
     * <p>
     * In some cases, InstanceManager can create the object the first time it's
     * requested. For more on that, see the class comment.
     * <p>
     * In most cases, system configuration assures the existence of a default
     * object, but this method also handles the case where one doesn't exist.
     * Use {@link #getDefault(java.lang.Class)} when the object is guaranteed to
     * exist.
     *
     * @param <T>  The type of the class
     * @param type The class Object for the item's type.
     * @return The default object for type.
     * @see #getOptionalDefault(java.lang.Class)
     */
    @CheckForNull
    public <T> T getInstance(@Nonnull Class<T> type) {
        log.trace("getOptionalDefault of type {}", type.getName());
        synchronized (type) {
            List<T> l = getInstances(type);
            if (l.isEmpty()) {
                // example of tracing where something is being initialized
                log.trace("jmri.implementation.SignalSpeedMap init", new Exception());
                if (traceFileActive) {
                    traceFilePrint("Start initialization: " + type.toString());
                    traceFileIndent++;
                }

                // check whether already working on this type
                InitializationState working = getInitializationState(type);
                Exception except = getInitializationException(type);
                setInitializationState(type, InitializationState.STARTED);
                if (working == InitializationState.STARTED) {
                    log.error("Proceeding to initialize {} while already in initialization", type,
                            new Exception("Thread \"" + Thread.currentThread().getName() + "\""));
                    log.error("    Prior initialization:", except);
                    if (traceFileActive) {
                        traceFilePrint("*** Already in process ***");
                    }
                } else if (working == InitializationState.DONE) {
                    log.error("Proceeding to initialize {} but initialization is marked as complete", type,
                            new Exception("Thread \"" + Thread.currentThread().getName() + "\""));
                }

                // see if can autocreate
                log.debug("    attempt auto-create of {}", type.getName());
                if (InstanceManagerAutoDefault.class.isAssignableFrom(type)) {
                    try {
                        T obj = type.getConstructor((Class[]) null).newInstance((Object[]) null);
                        l.add(obj);
                        // obj has been added, now initialize it if needed
                        if (obj instanceof InstanceManagerAutoInitialize) {
                            ((InstanceManagerAutoInitialize) obj).initialize();
                        }
                        log.debug("      auto-created default of {}", type.getName());
                    } catch (
                            NoSuchMethodException |
                            InstantiationException |
                            IllegalAccessException |
                            InvocationTargetException e) {
                        log.error("Exception creating auto-default object for {}", type.getName(), e); // unexpected
                        setInitializationState(type, InitializationState.FAILED);
                        if (traceFileActive) {
                            traceFileIndent--;
                            traceFilePrint("End initialization (no object) A: " + type.toString());
                        }
                        return null;
                    }
                    setInitializationState(type, InitializationState.DONE);
                    if (traceFileActive) {
                        traceFileIndent--;
                        traceFilePrint("End initialization A: " + type.toString());
                    }
                    return l.get(l.size() - 1);
                }
                // see if initializer can handle
                log.debug("    attempt initializer create of {}", type.getName());
                if (initializers.containsKey(type)) {
                    try {
                        @SuppressWarnings("unchecked")
                        T obj = (T) initializers.get(type).getDefault(type);
                        log.debug("      initializer created default of {}", type.getName());
                        l.add(obj);
                        // obj has been added, now initialize it if needed
                        if (obj instanceof InstanceManagerAutoInitialize) {
                            ((InstanceManagerAutoInitialize) obj).initialize();
                        }
                        setInitializationState(type, InitializationState.DONE);
                        if (traceFileActive) {
                            traceFileIndent--;
                            traceFilePrint("End initialization I: " + type.toString());
                        }
                        return l.get(l.size() - 1);
                    } catch (IllegalArgumentException ex) {
                        log.error("Known initializer for {} does not provide a default instance for that class",
                                type.getName());
                    }
                } else {
                    log.debug("        no initializer registered for {}", type.getName());
                }

                // don't have, can't make
                setInitializationState(type, InitializationState.FAILED);
                if (traceFileActive) {
                    traceFileIndent--;
                    traceFilePrint("End initialization (no object) E: " + type.toString());
                }
                return null;
            }
            return l.get(l.size() - 1);
        }
    }

    /**
     * Retrieve the last object of type T that was registered with
     * {@link #store(java.lang.Object, java.lang.Class)} wrapped in an
     * {@link java.util.Optional}.
     * <p>
     * Unless specifically set, the default is the last object stored, see the
     * {@link #setDefault(java.lang.Class, java.lang.Object)} method.
     * <p>
     * In some cases, InstanceManager can create the object the first time it's
     * requested. For more on that, see the class comment.
     * <p>
     * In most cases, system configuration assures the existence of a default
     * object, but this method also handles the case where one doesn't exist.
     * Use {@link #getDefault(java.lang.Class)} when the object is guaranteed to
     * exist.
     *
     * @param <T>  the type of the default class
     * @param type the class Object for the default type
     * @return the default wrapped in an Optional or an empty Optional if the
     *         default is null
     * @see #getNullableDefault(java.lang.Class)
     */
    @Nonnull
    public static <T> Optional<T> getOptionalDefault(@Nonnull Class< T> type) {
        return Optional.ofNullable(InstanceManager.getNullableDefault(type));
    }

    /**
     * Set an object of type T as the default for that type.
     * <p>
     * Also registers (stores) the object if not already present.
     * <p>
     * Now, we do that moving the item to the back of the list; see the
     * {@link #getDefault} method
     *
     * @param <T>  The type of the class
     * @param type The Class object for val
     * @param item The object to make default for type
     * @return The default for type (normally this is the item passed in)
     */
    @Nonnull
    public static <T> T setDefault(@Nonnull Class< T> type, @Nonnull T item) {
        log.trace("setDefault for type {}", type.getName());
        if (item == null) {
            NullPointerException npe = new NullPointerException();
            log.error("Should not set default of type {} to null value", type.getName());
            throw npe;
        }
        Object oldDefault = containsDefault(type) ? getNullableDefault(type) : null;
        List<T> l = getList(type);
        l.remove(item);
        l.add(item);
        if (oldDefault == null || !oldDefault.equals(item)) {
            getDefault().pcs.firePropertyChange(getDefaultsPropertyName(type), oldDefault, item);
        }
        return getDefault(type);
    }

    /**
     * Check if a default has been set for the given type.
     * <p>
     * As a side-effect, then (a) ensures that the list for the given
     * type exists, though it may be empty, and (b) if it had to create
     * the list, a PropertyChangeEvent is fired to denote that.
     *
     * @param <T>  The type of the class
     * @param type The class type
     * @return true if an item is available as a default for the given type;
     *         false otherwise
     */
    public static <T> boolean containsDefault(@Nonnull Class<T> type) {
        List<T> l = getList(type);
        return !l.isEmpty();
    }

    /**
     * Check if a particular type has been initialized without
     * triggering an automatic initialization. The existence or
     * non-existence of the corresponding list is not changed, and
     * no PropertyChangeEvent is fired.
     *
     * @param <T>  The type of the class
     * @param type The class type
     * @return true if an item is available as a default for the given type;
     *         false otherwise
     */
    public static <T> boolean isInitialized(@Nonnull Class<T> type) {
        return getDefault().managerLists.get(type) != null;
    }


    /**
     * Dump generic content of InstanceManager by type.
     *
     * @return A formatted multiline list of managed objects
     */
    @Nonnull
    public static String contentsToString() {

        StringBuilder retval = new StringBuilder();
        getDefault().managerLists.keySet().stream().forEachOrdered(c -> {
            retval.append("List of ");
            retval.append(c);
            retval.append(" with ");
            retval.append(Integer.toString(getList(c).size()));
            retval.append(" objects\n");
            getList(c).stream().forEachOrdered(o -> {
                retval.append("    ");
                retval.append(o.getClass().toString());
                retval.append("\n");
            });
        });
        return retval.toString();
    }

    /**
     * Get a list of stored types
     * @return A unmodifiable list of the currently stored types
     */
    public static Set<Class<?>> getInstanceClasses() {
        return Collections.unmodifiableSet(getDefault().managerLists.keySet());
    }
    
    /**
     * Remove notification on changes to specific types.
     *
     * @param l The listener to remove
     */
    public static synchronized void removePropertyChangeListener(PropertyChangeListener l) {
        getDefault().pcs.removePropertyChangeListener(l);
    }

    /**
     * Remove notification on changes to specific types.
     *
     * @param propertyName the property being listened for
     * @param l            The listener to remove
     */
    public static synchronized void removePropertyChangeListener(String propertyName, PropertyChangeListener l) {
        getDefault().pcs.removePropertyChangeListener(propertyName, l);
    }

    /**
     * Register for notification on changes to specific types.
     *
     * @param l The listener to add
     */
    public static synchronized void addPropertyChangeListener(PropertyChangeListener l) {
        getDefault().pcs.addPropertyChangeListener(l);
    }

    /**
     * Register for notification on changes to specific types
     *
     * @param propertyName the property being listened for
     * @param l            The listener to add
     */
    public static synchronized void addPropertyChangeListener(String propertyName, PropertyChangeListener l) {
        getDefault().pcs.addPropertyChangeListener(propertyName, l);
    }

    /**
     * Get the property name included in the
     * {@link java.beans.PropertyChangeEvent} thrown when the default for a
     * specific class is changed.
     *
     * @param clazz the class being listened for
     * @return the property name
     */
    public static String getDefaultsPropertyName(Class<?> clazz) {
        return "default-" + clazz.getName();
    }

    /**
     * Get the property name included in the
     * {@link java.beans.PropertyChangeEvent} thrown when the list for a
     * specific class is changed.
     *
     * @param clazz the class being listened for
     * @return the property name
     */
    public static String getListPropertyName(Class<?> clazz) {
        return "list-" + clazz.getName();
    }

    /* ****************************************************************************
     *                   Primary Accessors - Left (for now)
     *
     *          These are so extensively used that we're leaving for later
     *                      Please don't create any more of these
     * ****************************************************************************/
    /**
     * May eventually be deprecated, use @{link #getDefault} directly.
     *
     * @return the default light manager. May not be the only instance.
     */
    public static LightManager lightManagerInstance() {
        return getDefault(LightManager.class);
    }

    /**
     * May eventually be deprecated, use @{link #getDefault} directly.
     *
     * @return the default memory manager. May not be the only instance.
     */
    public static MemoryManager memoryManagerInstance() {
        return getDefault(MemoryManager.class);
    }

    /**
     * May eventually be deprecated, use @{link #getDefault} directly.
     *
     * @return the default sensor manager. May not be the only instance.
     */
    public static SensorManager sensorManagerInstance() {
        return getDefault(SensorManager.class);
    }

    /**
     * May eventually be deprecated, use @{link #getDefault} directly.
     *
     * @return the default turnout manager. May not be the only instance.
     */
    public static TurnoutManager turnoutManagerInstance() {
        return getDefault(TurnoutManager.class);
    }

    /**
     * May eventually be deprecated, use @{link #getDefault} directly.
     *
     * @return the default throttle manager. May not be the only instance.
     */
    public static ThrottleManager throttleManagerInstance() {
        return getDefault(ThrottleManager.class);
    }

    /* ****************************************************************************
     *                   Old Style Setters - To be migrated
     *
     *                   Migrate away the JMRI uses of these.
     * ****************************************************************************/

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, TurnoutManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    public static void setTurnoutManager(TurnoutManager p) {
        log.debug(" setTurnoutManager");
        TurnoutManager apm = getDefault(TurnoutManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<Turnout>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: TurnoutManager default isn't an AbstractProxyManager<Turnout>");
        }
    }

    public static void setThrottleManager(ThrottleManager p) {
        store(p, ThrottleManager.class);
    }

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, TurnoutManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    public static void setLightManager(LightManager p) {
        log.debug(" setLightManager");
        LightManager apm = getDefault(LightManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<Light>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: LightManager default isn't an AbstractProxyManager<Light>");
        }
    }

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, ReporterManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    public static void setReporterManager(ReporterManager p) {
        log.debug(" setReporterManager");
        ReporterManager apm = getDefault(ReporterManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<Reporter>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: ReporterManager default isn't an AbstractProxyManager<Reporter>");
        }
    }

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, SensorManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    public static void setSensorManager(SensorManager p) {
        log.debug(" setSensorManager");
        SensorManager apm = getDefault(SensorManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<Sensor>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: SensorManager default isn't an AbstractProxyManager<Sensor>");
        }
    }

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, IdTagManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    public static void setIdTagManager(IdTagManager p) {
        log.debug(" setIdTagManager");
        IdTagManager apm = getDefault(IdTagManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<IdTag>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: IdTagManager default isn't an AbstractProxyManager<IdTag>");
        }
    }

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, MeterManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    static public void setMeterManager(MeterManager p) {
        log.debug(" setMeterManager");
        MeterManager apm = getDefault(MeterManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<Meter>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: MeterManager default isn't an AbstractProxyManager<Meter>");
        }
    }

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, TurnoutManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    static public void setAnalogIOManager(AnalogIOManager p) {
        log.debug(" setAnalogIOManager");
        AnalogIOManager apm = getDefault(AnalogIOManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<AnalogIO>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: AnalogIOManager default isn't an AbstractProxyManager<AnalogIO>");
        }
    }

    // Needs to have proxy manager converted to work
    // with current list of managers (and robust default
    // management) before this can be deprecated in favor of
    // store(p, TurnoutManager.class)
    @SuppressWarnings("unchecked") // AbstractProxyManager of the right type is type-safe by definition
    static public void setStringIOManager(StringIOManager p) {
        log.debug(" setStringIOManager");
        StringIOManager apm = getDefault(StringIOManager.class);
        if (apm instanceof ProxyManager<?>) { // <?> due to type erasure
            ((ProxyManager<StringIO>) apm).addManager(p);
        } else {
            log.error("Incorrect setup: StringIOManager default isn't an AbstractProxyManager<StringIO>");
        }
    }

    /* *************************************************************************** */

    /**
     * Default constructor for the InstanceManager.
     */
    public InstanceManager() {
        ServiceLoader.load(InstanceInitializer.class).forEach(provider ->
            provider.getInitalizes().forEach(cls -> {
                this.initializers.put(cls, provider);
                log.debug("Using {} to provide default instance of {}", provider.getClass().getName(), cls.getName());
            }));
    }

    /**
     * Get a list of all registered objects of type T.
     *
     * @param <T>  type of the class
     * @param type class Object for type T
     * @return a list of registered T instances with the manager or an empty
     *         list
     */
    @SuppressWarnings("unchecked") // the cast here is protected by the structure of the managerLists
    @Nonnull
    public <T> List<T> getInstances(@Nonnull Class<T> type) {
        log.trace("Get list of type {}", type.getName());
        synchronized (type) {
            if (managerLists.get(type) == null) {
                managerLists.put(type, new ArrayList<>());
                pcs.fireIndexedPropertyChange(getListPropertyName(type), 0, null, null);
            }
            return (List<T>) managerLists.get(type);
        }
    }


    /**
     * Get a list of all registered objects of a specific type.
     *
     * Intended for use with i.e. scripts where access to the class type is inconvenient.
     *
     * @param <T>  type of the class
     * @param className Fully qualified class name
     * @return a list of registered instances with the manager or an empty
     *         list
     * @throws IllegalArgumentException if the named class doesn't exist
     */
    @SuppressWarnings("unchecked") // the cast here is protected by the structure of the managerLists
    @Nonnull
    public <T> List<T> getInstances(@Nonnull String className) {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException ex) {
            log.error("No class found: {}", className);
            throw new IllegalArgumentException(ex);
        }
        log.trace("Get list of type {}", type.getName());
        synchronized (type) {
            if (managerLists.get(type) == null) {
                managerLists.put(type, new ArrayList<>());
                pcs.fireIndexedPropertyChange(getListPropertyName(type), 0, null, null);
            }
            return (List<T>) managerLists.get(type);
        }
    }


    /**
     * Call {@link jmri.Disposable#dispose()} on the passed in Object if and
     * only if the passed in Object is not held in any lists.
     * <p>
     * Realistically, JMRI can't ensure that all objects and combination of
     * objects held by the InstanceManager are threadsafe. Therefor dispose() is
     * called on the Event Dispatch Thread to reduce risk.
     *
     * @param disposable the Object to dispose of
     */
    private void dispose(@Nonnull Disposable disposable) {
        boolean canDispose = true;
        for (List<?> list : this.managerLists.values()) {
            if (list.contains(disposable)) {
                canDispose = false;
                break;
            }
        }
        if (canDispose) {
            ThreadingUtil.runOnGUI(disposable::dispose);
        }
    }

    /**
     * Clear all managed instances from the common instance manager, effectively
     * installing a new one.
     */
    public void clearAll() {
        log.debug("Clearing InstanceManager");
        if (traceFileActive) traceFileWriter.println("clearAll");

        // reset the instance manager, so future calls will invoke the new one
        LazyInstanceManager.resetInstanceManager();

        // continue to clean up this one
        new HashSet<>(managerLists.keySet()).forEach(this::clear);
        managerLists.keySet().forEach(type -> {
            if (getInitializationState(type) != InitializationState.NOTSET) {
                log.warn("list of {} was reinitialized during clearAll", type, new Exception());
                if (traceFileActive) traceFileWriter.println("WARN: list of "+type+" was reinitialized during clearAll");
            }
            if (!managerLists.get(type).isEmpty()) {
                log.warn("list of {} was not cleared, {} entries", type, managerLists.get(type).size(), new Exception());
                if (traceFileActive) traceFileWriter.println("WARN: list of "+type+" was not cleared, "+managerLists.get(type).size()+" entries");
            }
        });
        if (traceFileActive) {
            traceFileWriter.println(""); // marks new InstanceManager
            traceFileWriter.flush();
        }
    }

    /**
     * Clear all managed instances of a particular type from this
     * InstanceManager.
     *
     * @param <T>  the type of class to clear
     * @param type the type to clear
     */
    public <T> void clear(@Nonnull Class<T> type) {
        log.trace("Clearing managers of {}", type.getName());
        List<T> toClear = new ArrayList<>(getInstances(type));
        toClear.forEach(o -> remove(o, type));
        setInitializationState(type, InitializationState.NOTSET); // initialization will have to be redone
        managerLists.put(type, new ArrayList<>());
    }

    /**
     * A class for lazy initialization of the singleton class InstanceManager.
     *
     * See https://www.ibm.com/developerworks/library/j-jtp03304/
     */
    private static class LazyInstanceManager {

        private static InstanceManager instanceManager = new InstanceManager();

        /**
         * Get the InstanceManager.
         */
        public static InstanceManager getInstanceManager() {
            return instanceManager;
        }

        /**
         * Replace the (static) InstanceManager.
         */
        public static synchronized void resetInstanceManager() {
            try {
                instanceManager = new InstanceManager();
            } catch (Exception e) {
                log.error("can't create new InstanceManager");
            }
        }

    }

    /**
     * Get the default instance of the InstanceManager. This is used for
     * verifying the source of events fired by the InstanceManager.
     *
     * @return the default instance of the InstanceManager, creating it if
     *         needed
     */
    @Nonnull
    public static InstanceManager getDefault() {
        return LazyInstanceManager.getInstanceManager();
    }

    // support checking for overlapping intialization
    private enum InitializationState {
        NOTSET, // synonymous with no value for this stored
        NOTSTARTED,
        STARTED,
        FAILED,
        DONE
    }

    private static final class StateHolder {

        InitializationState state;
        Exception exception;

        StateHolder(InitializationState state, Exception exception) {
            this.state = state;
            this.exception = exception;
        }
    }

    private void setInitializationState(Class<?> type, InitializationState state) {
        log.trace("set state {} for {}", type, state);
        if (state == InitializationState.STARTED) {
            initState.put(type, new StateHolder(state, new Exception("Thread " + Thread.currentThread().getName())));
        } else {
            initState.put(type, new StateHolder(state, null));
        }
    }

    private InitializationState getInitializationState(Class<?> type) {
        StateHolder holder = initState.get(type);
        if (holder == null) {
            return InitializationState.NOTSET;
        }
        return holder.state;
    }

    private Exception getInitializationException(Class<?> type) {
        StateHolder holder = initState.get(type);
        if (holder == null) {
            return null;
        }
        return holder.exception;
    }

    private static final Logger log = LoggerFactory.getLogger(InstanceManager.class);

    // support creating a file with initialization summary information
    private static final boolean traceFileActive = log.isTraceEnabled(); // or manually force true
    private static final boolean traceFileAppend = false; // append from run to run
    private int traceFileIndent = 1; // used to track overlap, but note that threads are parallel
    private static final String traceFileName = "instanceManagerSequence.txt";  // use a standalone name
    private static PrintWriter traceFileWriter;

    static {
        PrintWriter tempWriter = null;
        try {
            tempWriter = (traceFileActive
                    ? new PrintWriter(new BufferedWriter(new FileWriter(new File(traceFileName), traceFileAppend)))
                    : null);
        } catch (java.io.IOException e) {
            log.error("failed to open log file", e);
        } finally {
            traceFileWriter = tempWriter;
        }
    }

    private void traceFilePrint(String msg) {
        String pad = org.apache.commons.lang3.StringUtils.repeat(' ', traceFileIndent * 2);
        String threadName = "[" + Thread.currentThread().getName() + "]";
        String threadNamePad = org.apache.commons.lang3.StringUtils.repeat(' ', Math.max(25 - threadName.length(), 0));
        String text = threadName + threadNamePad + "|" + pad + msg;
        traceFileWriter.println(text);
        traceFileWriter.flush();
        log.trace(text);
    }

}
