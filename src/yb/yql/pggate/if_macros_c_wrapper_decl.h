// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

// These macros generate C function prototypes that get compiled into the yb_pggate C++ library
// from YB C API DSL. This header is used to instantiate both C and C++ versions of C function
// prototypes, but the functions themselves are mostly called from C code. Note that for functions
// returning Status or Result, the functions generated by this file are not the most convenient
// ones to call from the PostgreSQL C code. The functions generated here have a _Status prefix and
// return a YBCStatus, with the intended return value being passed through an out-parameter, and
// there is another layer of inline C functions generated by macros in the
// if_macros_c_pg_wrapper_inl.h file that convert the YBCStatus into a PostgreSQL ereport and return
// the original return value.

#include "yb/yql/pggate/if_macros_common.h"
#include "yb/yql/pggate/if_macros_c_wrapper_common.h"

// This is used in the beginning of a YB C API class definition in the DSL. When generating the
// C API header, we take the opportunity to define an opaque C struct that will represent instances
// of this class on the C side.
#ifdef __cplusplus
#define YBC_CLASS_START \
    namespace yb { namespace pggate { class YBC_CURRENT_CLASS; } } \
    typedef class ::yb::pggate::YBC_CURRENT_CLASS *YBC_CURRENT_CLASS_C_HANDLE_TYPE;
#else
// In C code, YBC<class_name> is just a pointer to an anonymous struct.
#define YBC_CLASS_START \
    typedef struct BOOST_PP_CAT(Opaque_, YBC_CURRENT_CLASS) *YBC_CURRENT_CLASS_C_HANDLE_TYPE;
#endif  // __cplusplus

#define YBC_CLASS_START_INHERIT_FROM YBC_CLASS_START
#define YBC_CLASS_START_REF_COUNTED_THREAD_SAFE YBC_CLASS_START

// Generates a C wrapper function to create an instance of the class. E.g. YBCMyClass_New.
#define YBC_CONSTRUCTOR(argument_descriptions) \
    YBC_CONSTRUCTOR_WRAPPER_PROTOTYPE(argument_descriptions);
#define YBC_CONSTRUCTOR_NO_ARGS YBC_CONSTRUCTOR_WRAPPER_PROTOTYPE_NO_ARGS;

// Generates a declaration of a C wrapper function to call a class member function.
// E.g. YBCMyClass_SomeFunction.
#define YBC_METHOD(return_type, method_name, argument_descriptions) \
    YBC_METHOD_WRAPPER_PROTOTYPE(return_type, method_name, argument_descriptions);
#define YBC_METHOD_NO_ARGS(return_type, method_name) \
    YBC_METHOD_WRAPPER_PROTOTYPE_NO_ARGS(return_type, method_name);

// Generates a declaration of a C wrapper function to call a class member function returning a
// YBCStatus, e.g. YBCMyClass_SomeFunction_Status. We are using a _Status suffix because we also
// create a wrapper function on the PostgresSQL side without this suffix that automatically handles
// the error.
#define YBC_STATUS_METHOD(method_name, argument_descriptions) \
    __attribute__((warn_unused_result)) \
    YBC_METHOD(YBCStatus, BOOST_PP_CAT(method_name, _Status), argument_descriptions)
#define YBC_STATUS_METHOD_NO_ARGS(method_name) \
    __attribute__((warn_unused_result)) \
    YBC_METHOD_NO_ARGS(YBCStatus, BOOST_PP_CAT(method_name, _Status))

// Generates a declaration ofa a wrapper function to call a class member function that returns
// Result<return_type>. We return the status in the last out-parameter, because we assume the status
// will be OK in most cases.
#define YBC_RESULT_METHOD(return_type, method_name, argument_descriptions) \
    __attribute__((warn_unused_result)) YBC_RESULT_METHOD_WRAPPER_PROTOTYPE( \
        return_type, BOOST_PP_CAT(method_name, _Status), argument_descriptions);
