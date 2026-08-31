
#ifndef JPEGLI_CMS_EXPORT_H
#define JPEGLI_CMS_EXPORT_H

#ifdef JPEGLI_CMS_STATIC_DEFINE
#  define JPEGLI_CMS_EXPORT
#  define JPEGLI_CMS_NO_EXPORT
#else
#  ifndef JPEGLI_CMS_EXPORT
#    ifdef jpegli_cms_EXPORTS
        /* We are building this library */
#      define JPEGLI_CMS_EXPORT 
#    else
        /* We are using this library */
#      define JPEGLI_CMS_EXPORT 
#    endif
#  endif

#  ifndef JPEGLI_CMS_NO_EXPORT
#    define JPEGLI_CMS_NO_EXPORT 
#  endif
#endif

#ifndef JPEGLI_CMS_DEPRECATED
#  define JPEGLI_CMS_DEPRECATED __attribute__ ((__deprecated__))
#endif

#ifndef JPEGLI_CMS_DEPRECATED_EXPORT
#  define JPEGLI_CMS_DEPRECATED_EXPORT JPEGLI_CMS_EXPORT JPEGLI_CMS_DEPRECATED
#endif

#ifndef JPEGLI_CMS_DEPRECATED_NO_EXPORT
#  define JPEGLI_CMS_DEPRECATED_NO_EXPORT JPEGLI_CMS_NO_EXPORT JPEGLI_CMS_DEPRECATED
#endif

#if 0 /* DEFINE_NO_DEPRECATED */
#  ifndef JPEGLI_CMS_NO_DEPRECATED
#    define JPEGLI_CMS_NO_DEPRECATED
#  endif
#endif

#endif /* JPEGLI_CMS_EXPORT_H */
