/* Minimal CPython launcher. Shipped as libpythonexe.so so Android extracts it
 * into nativeLibraryDir, the only app location that may be exec()ed. */
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
