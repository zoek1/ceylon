package com.redhat.ceylon.compiler.java.runtime.metamodel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import ceylon.language.Callable;
import ceylon.language.Sequential;
import ceylon.language.nothing_;
import ceylon.language.metamodel.Class$impl;

import com.redhat.ceylon.compiler.java.metadata.Ceylon;
import com.redhat.ceylon.compiler.java.metadata.Ignore;
import com.redhat.ceylon.compiler.java.metadata.TypeInfo;
import com.redhat.ceylon.compiler.java.metadata.TypeParameter;
import com.redhat.ceylon.compiler.java.metadata.TypeParameters;
import com.redhat.ceylon.compiler.java.metadata.Variance;
import com.redhat.ceylon.compiler.java.runtime.model.TypeDescriptor;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;

@Ceylon(major = 5)
@com.redhat.ceylon.compiler.java.metadata.Class
@TypeParameters({
    @TypeParameter(value = "Type", variance = Variance.OUT),
    @TypeParameter(value = "Arguments", variance = Variance.IN, satisfies = "ceylon.language::Sequential<ceylon.language::Anything>"),
    })
public class AppliedClassType<Type, Arguments extends Sequential<? extends Object>> 
    extends AppliedClassOrInterfaceType<Type>
    implements ceylon.language.metamodel.Class<Type, Arguments>, Callable<Type> {

    private TypeDescriptor $reifiedArguments;
    private MethodHandle constructor;
    private Object instance;
    private int firstDefaulted;
    private MethodHandle[] dispatch;
    
    public AppliedClassType(com.redhat.ceylon.compiler.typechecker.model.ProducedType producedType, Object instance) {
        super(producedType);
        this.instance = instance;
    }

    @Override
    @Ignore
    public Class$impl<Type, Arguments> $ceylon$language$metamodel$Class$impl() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    @TypeInfo("ceylon.language.metamodel.declaration::ClassDeclaration")
    public ceylon.language.metamodel.declaration.ClassDeclaration getDeclaration() {
        return (ceylon.language.metamodel.declaration.ClassDeclaration) super.getDeclaration();
    }

    @Override
    protected void init() {
        super.init();
        com.redhat.ceylon.compiler.typechecker.model.Class decl = (com.redhat.ceylon.compiler.typechecker.model.Class) producedType.getDeclaration();

        // anonymous classes don't have parameter lists
        if(!decl.isAnonymous()){
            initParameters(decl);
        }else{
            this.$reifiedArguments = TypeDescriptor.NothingType;
        }
    }

    private void initParameters(com.redhat.ceylon.compiler.typechecker.model.Class decl) {
        List<Parameter> parameters = decl.getParameterLists().get(0).getParameters();
        com.redhat.ceylon.compiler.typechecker.model.ProducedType tupleType 
            = com.redhat.ceylon.compiler.typechecker.analyzer.Util.getParameterTypesAsTupleType(decl.getUnit(), parameters, producedType);
        this.$reifiedArguments = Metamodel.getTypeDescriptorForProducedType(tupleType);
        
        this.firstDefaulted = Metamodel.getFirstDefaultedParameter(parameters);

        Object[] defaultedMethods = null;
        if(firstDefaulted != -1){
            // if we have 2 params and first is defaulted we need 2 + 1 - 0 = 3 methods:
            // f(), f(a) and f(a, b)
            this.dispatch = new MethodHandle[parameters.size() + 1 - firstDefaulted];
            defaultedMethods = new Object[dispatch.length];
        }

        // FIXME: delay constructor setup for when we actually use it?
        // FIXME: finding the right MethodHandle for the constructor could actually be done in the Class declaration
        java.lang.Class<?> javaClass = Metamodel.getJavaClass(declaration.declaration);
        // FIXME: deal with Java classes
        // FIXME: faster lookup with types? but then we have to deal with erasure and stuff
        Object found = null;
        if(!javaClass.isMemberClass()){
            for(Constructor<?> constr : javaClass.getDeclaredConstructors()){
                if(constr.isAnnotationPresent(Ignore.class)){
                    // it's likely an overloaded constructor
                    // FIXME: proper checks
                    if(firstDefaulted != -1){
                        int params = constr.getParameterTypes().length;
                        defaultedMethods[params - firstDefaulted] = constr;
                    }
                    continue;
                }
                // FIXME: deal with private stuff?
                if(found != null){
                    throw new RuntimeException("More than one constructor found for: "+javaClass+", 1st: "+found+", 2nd: "+constr);
                }
                found = constr;
            }
        }else{
            String builderName = declaration.getName() + "$new";
            // FIXME: this probably doesn't work for local classes
            // FIXME: perhaps store and access the container class literal from an extra param of @Container?
            java.lang.Class<?> outerJavaClass = Metamodel.getJavaClass((Declaration) declaration.declaration.getContainer());
            for(Method meth : outerJavaClass.getDeclaredMethods()){
                // FIXME: we need a better way to look things up: they're all @Ignore...
//                if(meth.isAnnotationPresent(Ignore.class))
//                    continue;
                if(!meth.getName().equals(builderName))
                    continue;
                // FIXME: proper checks
                if(firstDefaulted != -1){
                    int params = meth.getParameterTypes().length;
                    if(params != parameters.size()){
                        defaultedMethods[params - firstDefaulted] = meth;
                        continue;
                    }
                }

                // FIXME: deal with private stuff?
                if(found != null){
                    throw new RuntimeException("More than one constructor method found for: "+javaClass+", 1st: "+found+", 2nd: "+meth);
                }
                found = meth;
            }
        }
        if(found != null){
            constructor = reflectionToMethodHandle(found, javaClass, instance, producedType);
            if(defaultedMethods != null){
                // this won't find the last one, but it's method
                int i=0;
                for(;i<defaultedMethods.length-1;i++){
                    // FIXME: proper checks
                    dispatch[i] = reflectionToMethodHandle(defaultedMethods[i], javaClass, instance, producedType);
                }
                dispatch[i] = constructor;
            }
        }
    }

    private MethodHandle reflectionToMethodHandle(Object found, Class<?> javaClass, Object instance2, ProducedType producedType) {
        MethodHandle constructor = null;
        java.lang.Class<?>[] parameterTypes;
        try {
            if(!javaClass.isMemberClass()){
                constructor = MethodHandles.lookup().unreflectConstructor((java.lang.reflect.Constructor<?>)found);
                parameterTypes = ((java.lang.reflect.Constructor<?>)found).getParameterTypes();
            }else{
                constructor = MethodHandles.lookup().unreflect((Method) found);
                parameterTypes = ((java.lang.reflect.Method)found).getParameterTypes();
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Problem getting a MH for constructor for: "+javaClass, e);
        }
        // we need to cast to Object because this is what comes out when calling it in $call
        if(instance != null)
            constructor = constructor.bindTo(instance);
        constructor = constructor.asType(MethodType.methodType(Object.class, parameterTypes));
        int typeParametersCount = javaClass.getTypeParameters().length;
        // insert any required type descriptors
        // FIXME: only if it's expecting them!
        if(typeParametersCount != 0){
            List<ProducedType> typeArguments = producedType.getTypeArgumentList();
            constructor = MethodHandleUtil.insertReifiedTypeArguments(constructor, 0, typeArguments);
        }
        // now convert all arguments (we may need to unbox)
        constructor = MethodHandleUtil.unboxArguments(constructor, typeParametersCount, 0, parameterTypes);
        
        return constructor;
    }

    @Override
    public Type $call() {
        checkInit();
        if(constructor == null)
            throw new RuntimeException("No constructor found for: "+declaration.getName());
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact();
            // FIXME: proper checks
            return (Type)dispatch[0].invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke constructor for "+declaration.getName(), e);
        }
    }

    @Override
    public Type $call(Object arg0) {
        checkInit();
        if(constructor == null)
            throw new RuntimeException("No constructor found for: "+declaration.getName());
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact(arg0);
            // FIXME: proper checks
            return (Type)dispatch[1-firstDefaulted].invokeExact(arg0);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke constructor for "+declaration.getName(), e);
        }
    }

    @Override
    public Type $call(Object arg0, Object arg1) {
        checkInit();
        if(constructor == null)
            throw new RuntimeException("No constructor found for: "+declaration.getName());
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact(arg0, arg1);
            // FIXME: proper checks
            return (Type)dispatch[2-firstDefaulted].invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke constructor for "+declaration.getName(), e);
        }
    }

    @Override
    public Type $call(Object arg0, Object arg1, Object arg2) {
        checkInit();
        if(constructor == null)
            throw new RuntimeException("No constructor found for: "+declaration.getName());
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact(arg0, arg1, arg2);
            // FIXME: proper checks
            return (Type)dispatch[3-firstDefaulted].invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke constructor for "+declaration.getName(), e);
        }
    }

    @Override
    public Type $call(Object... args) {
        checkInit();
        if(constructor == null)
            throw new RuntimeException("No constructor found for: "+declaration.getName());
        try {
            if(firstDefaulted == -1)
                // FIXME: this does not do invokeExact and does boxing/widening
                return (Type)constructor.invokeWithArguments(args);
            // FIXME: proper checks
            return (Type)dispatch[args.length-firstDefaulted].invokeWithArguments(args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke constructor for "+declaration.getName(), e);
        }
    }

    @Override
    public short $getVariadicParameterIndex() {
        // TODO Auto-generated method stub
        return -1;
    }

    @Override
    public TypeDescriptor $getType() {
        checkInit();
        return TypeDescriptor.klass(AppliedClassType.class, $reifiedType, $reifiedArguments);
    }
}
