# R8 keeps manifest components and any class a resource names. Nothing here is
# reached by reflection: the views are built in code, the JNI layer never calls
# back into Java, and the accessibility service is declared in the manifest.

# Native entry points are looked up by symbol in the .so files.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
