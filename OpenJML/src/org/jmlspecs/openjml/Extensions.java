/*
 * This file is part of the OpenJML project. 
 * Author: David R. Cok
 */
package org.jmlspecs.openjml;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.jmlspecs.annotation.Nullable;
import org.jmlspecs.openjml.ext.Elemtype;
import org.jmlspecs.openjml.ext.Erasure;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.parser.ExpressionExtension;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

/* FIXME - do more to implement extensions */

/** This class manages extensions. It finds them at application startup, and
 * creates instances of individual extension classes for compilation contexts.
 * This class is a tool in the compiler tool chain, though without a
 * corresponding OpenJDK tool to mimic. This class is not expected to be
 * derived.
 *  */
public class Extensions {
    protected static final Context.Key<Extensions> extensionsKey =
            new Context.Key<Extensions>();

    /** The compilation context, set when the class is instantiated */
    protected /*@ non_null */ Context context;

    //@ public constraint context == \old(context);
    
    protected static Utils utils;
    
    /** A constructor for the class; this class should not be
     * instantiated directly by users - use instance instead to get a
     * singleton instance (for each compilation context).
     */
    protected Extensions(Context context) {
        context.put(extensionsKey, this);
        this.context = context;
    }
    
    /** A factory that returns a singleton instance of the class for the
     * given compilation context.
     */
    static public Extensions instance(Context context) {
        Extensions instance = context.get(extensionsKey);
        if (instance == null)
            instance = new Extensions(context);
        return instance;
    }
    
    /** Returns a (derived instance of) ExpressionExtension if there is one associated
     * with the given token.  Returns null if there is no
     * extension for the given token.
     * @param pos the position of the token, for error reporting
     * @param token the extension token
     * @return an instance of a ExpressionExtension object, or null if unrecoverable error
     */
    public @Nullable ExpressionExtension find(int pos, JmlToken token) {
        ExpressionExtension e = extensionInstances.get(token);
        if (e == null) {
            Class<? extends ExpressionExtension> c = extensionClasses.get(token);
            if (c == null) {
                Log.instance(context).error(pos,"jml.failure.to.create.ExpressionExtension",token.internedName());
                return null;
            }
            try {
                Constructor<? extends ExpressionExtension> constructor = c.getDeclaredConstructor(Context.class);
                ExpressionExtension instance = constructor.newInstance(context);
                extensionInstances.put(token,instance);
                e = instance;
            } catch (Exception ee) {
                Log.instance(context).error(pos,"jml.failure.to.create.ExpressionExtension",token.internedName());
                return null;
            }
        }
        return e;
    }
    
    /** The list of classes that add extensions to the Parser */
    static Class<?>[] extensions = { Elemtype.class, Erasure.class };

    /** A map from token type to the extension class that implements the token */
    static protected Map<JmlToken,Class<? extends ExpressionExtension>> extensionClasses = new HashMap<JmlToken,Class<? extends ExpressionExtension>>();
    protected Map<JmlToken,ExpressionExtension> extensionInstances = new HashMap<JmlToken,ExpressionExtension>();
    
    // FIXME - change this to find extensions in various packages
    // This static block runs through all the extension classes and adds
    // appropriate information to the HashMap above, so extensions can be 
    // looked up at runtime.
    public static void register(Context context) {
        Utils utils = Utils.instance(context);
        Package p = Package.getPackage("org.jmlspecs.openjml.ext");
        java.util.List<Class<?>> extensions;
        try {
            extensions = findClasses(context,p);
            JmlToken[] tokens;
            for (Class<?> cc: extensions) {
                if (!ExpressionExtension.class.equals(cc.getSuperclass())) continue;
                Class<? extends ExpressionExtension> c = (Class<? extends ExpressionExtension>)cc;
                try {
                    Method m = c.getMethod("tokens");
                    tokens = (JmlToken[])m.invoke(null);
                } catch (Exception e) {
                    continue;
                }
                for (JmlToken t: tokens) {
                    extensionClasses.put(t, c);
                }
            }
        } catch (Exception e) {
            System.out.println(e);
            // FIXME - error
        }
        
    }
    
    public static java.util.List<Class<?>> findClasses(Context context, Package p) throws java.io.IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        assert classLoader != null;
        String packageName = p.getName();
        String path = packageName.replace('.', '/');
        Enumeration<URL> resources = classLoader.getResources(path);
        ArrayList<Class<?>> classes = new ArrayList<Class<?>>();
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            File dir = new File(resource.getFile());
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f: files) {
                if (f.isDirectory()) continue;
                String name = f.getName();
                int k = name.indexOf('.');
                if (k < 0) continue;
                name = name.substring(0,k);
                String fullname = packageName + "." + name;
                try {
                    Class<?> c = Class.forName(fullname);
                    if (Modifier.isAbstract(c.getModifiers())) continue;
                    Method m = c.getMethod("register",Context.class);
                    m.invoke(null,context); // Purposely fails if there is no static register method
                    classes.add(c);
                    if (Utils.instance(context).jmlverbose >= Utils.JMLDEBUG) Log.instance(context).noticeWriter.println("Registered extension " + fullname);
                } catch (Exception e) {
                    // Just skip if there is any exception, such as a
                    // Class or Method not found.
                    if (Utils.instance(context).jmlverbose >= Utils.JMLDEBUG) Log.instance(context).noticeWriter.println("Failed to register " + fullname);
                    continue;
                }
            }
        }
        return classes;
    }
    
}