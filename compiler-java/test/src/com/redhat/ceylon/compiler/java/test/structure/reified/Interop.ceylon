shared class CeylonClass<Element>()
    extends JavaClass<Element>()
    satisfies JavaInterface<Element>{}

shared interface CeylonInterface<Element> satisfies JavaInterface<Element>{}

void reifiedInstantiateInterop(){
    value c = CeylonClass<Integer>();
    value c2 = JavaClass<Integer>();
    value constr = JavaClass<Integer>;
    value c3 = constr();
}