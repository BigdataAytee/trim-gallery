# see  https://gitlab.kitware.com/cmake/community/-/wikis/FAQ#can-i-do-make-uninstall-with-cmake

if(NOT EXISTS "/home/user/trim-gallery/shared/native/build-host/install_manifest.txt")
  message(FATAL_ERROR "Cannot find install manifest: /home/user/trim-gallery/shared/native/build-host/install_manifest.txt")
endif()

# Read the install manifest
file(READ "/home/user/trim-gallery/shared/native/build-host/install_manifest.txt" files)
string(REGEX REPLACE "\n" ";" files "${files}")

# Uninstall files
foreach(file ${files})
  message(STATUS "Uninstalling $ENV{DESTDIR}${file}")
  if(IS_SYMLINK "$ENV{DESTDIR}${file}" OR EXISTS "$ENV{DESTDIR}${file}")
    execute_process(
      COMMAND "/usr/bin/cmake" -E remove "$ENV{DESTDIR}${file}"
      OUTPUT_VARIABLE rm_out
      RESULT_VARIABLE rm_retval
    )
    if(NOT "${rm_retval}" STREQUAL 0)
      message(FATAL_ERROR "Problem when removing $ENV{DESTDIR}${file}")
    endif()
  else(IS_SYMLINK "$ENV{DESTDIR}${file}" OR EXISTS "$ENV{DESTDIR}${file}")
    message(STATUS "File $ENV{DESTDIR}${file} does not exist.")
  endif()
endforeach()

# Remove empty directories
foreach(file ${files})
  get_filename_component(dir "$ENV{DESTDIR}${file}" DIRECTORY)
  while(NOT "${dir}" STREQUAL "/" AND EXISTS "${dir}")
    file(GLOB dir_content "${dir}/*")
    if(dir_content)
      break()  # Directory is not empty, stop removing
    endif()
    message(STATUS "Removing empty directory ${dir}")
    file(REMOVE_RECURSE "${dir}")
    get_filename_component(dir "${dir}" DIRECTORY)  # Move up one directory
  endwhile()
endforeach()

