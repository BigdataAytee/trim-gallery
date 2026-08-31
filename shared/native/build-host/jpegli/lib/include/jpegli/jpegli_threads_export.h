
#ifndef JPEGLI_THREADS_EXPORT_H
#define JPEGLI_THREADS_EXPORT_H

#ifdef JPEGLI_THREADS_STATIC_DEFINE
#  define JPEGLI_THREADS_EXPORT
#  define JPEGLI_THREADS_NO_EXPORT
#else
#  ifndef JPEGLI_THREADS_EXPORT
#    ifdef JPEGLI_THREADS_INTERNAL_LIBRARY_BUILD
        /* We are building this library */
#      define JPEGLI_THREADS_EXPORT __attribute__((visibility("default")))
#    else
        /* We are using this library */
#      define JPEGLI_THREADS_EXPORT __attribute__((visibility("default")))
#    endif
#  endif

#  ifndef JPEGLI_THREADS_NO_EXPORT
#    define JPEGLI_THREADS_NO_EXPORT __attribute__((visibility("hidden")))
#  endif
#endif

#ifndef JPEGLI_THREADS_DEPRECATED
#  define JPEGLI_THREADS_DEPRECATED __attribute__ ((__deprecated__))
#endif

#ifndef JPEGLI_THREADS_DEPRECATED_EXPORT
#  define JPEGLI_THREADS_DEPRECATED_EXPORT JPEGLI_THREADS_EXPORT JPEGLI_THREADS_DEPRECATED
#endif

#ifndef JPEGLI_THREADS_DEPRECATED_NO_EXPORT
#  define JPEGLI_THREADS_DEPRECATED_NO_EXPORT JPEGLI_THREADS_NO_EXPORT JPEGLI_THREADS_DEPRECATED
#endif

#if 0 /* DEFINE_NO_DEPRECATED */
#  ifndef JPEGLI_THREADS_NO_DEPRECATED
#    define JPEGLI_THREADS_NO_DEPRECATED
#  endif
#endif

#endif /* JPEGLI_THREADS_EXPORT_H */
