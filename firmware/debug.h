// AirBox V2 logging.
//
// Every subsystem prints under its own tag, e.g. [I][sen66], so you can see at
// a glance which stage produced which line. Pick the verbosity with LOG_LEVEL
// below; disabled levels compile to nothing, so leaving log calls in the code
// costs nothing at lower levels.
#pragma once
#include <Arduino.h>

//  Serial log verbosity.
//    LOG_NONE  - silent
//    LOG_ERROR - failures only
//    LOG_WARN  - + warnings (skipped stages, suspicious readings)
//    LOG_INFO  - + normal per-stage results          (good default)
//    LOG_DEBUG - + raw values and per-attempt detail  (most verbose)
#define LOG_NONE 0
#define LOG_ERROR 1
#define LOG_WARN 2
#define LOG_INFO 3
#define LOG_DEBUG 4

#define LOG_LEVEL LOG_INFO

//  Logging macros.
//  Each call takes a short tag and a printf-style message. A message string is
//  ALWAYS required, do not call a macro with the tag alone:
//      LOGI("guard", "temperature: %.2f C", t);   // correct
//      LOGI("guard");                              // will not compile
//  When a level is disabled the macro expands to nothing and its arguments are
//  not evaluated.
#if LOG_LEVEL >= LOG_ERROR
#define LOGE(tag, ...)                                                         \
  do {                                                                         \
    Serial.printf("[E][%s] ", tag);                                            \
    Serial.printf(__VA_ARGS__);                                                \
    Serial.println();                                                          \
  } while (0)
#else
#define LOGE(tag, ...)                                                         \
  do {                                                                         \
  } while (0)
#endif

#if LOG_LEVEL >= LOG_WARN
#define LOGW(tag, ...)                                                         \
  do {                                                                         \
    Serial.printf("[W][%s] ", tag);                                            \
    Serial.printf(__VA_ARGS__);                                                \
    Serial.println();                                                          \
  } while (0)
#else
#define LOGW(tag, ...)                                                         \
  do {                                                                         \
  } while (0)
#endif

#if LOG_LEVEL >= LOG_INFO
#define LOGI(tag, ...)                                                         \
  do {                                                                         \
    Serial.printf("[I][%s] ", tag);                                            \
    Serial.printf(__VA_ARGS__);                                                \
    Serial.println();                                                          \
  } while (0)
#else
#define LOGI(tag, ...)                                                         \
  do {                                                                         \
  } while (0)
#endif

#if LOG_LEVEL >= LOG_DEBUG
#define LOGD(tag, ...)                                                         \
  do {                                                                         \
    Serial.printf("[D][%s] ", tag);                                            \
    Serial.printf(__VA_ARGS__);                                                \
    Serial.println();                                                          \
  } while (0)
#else
#define LOGD(tag, ...)                                                         \
  do {                                                                         \
  } while (0)
#endif

// Stage banner: prints a blank line then a header, e.g.
//   LOG_STAGE("SEN66 AIR QUALITY");  ->  ===== SEN66 AIR QUALITY =====
#if LOG_LEVEL >= LOG_INFO
#define LOG_STAGE(name)                                                        \
  do {                                                                         \
    Serial.println();                                                          \
    Serial.print("===== ");                                                    \
    Serial.print(name);                                                        \
    Serial.println(" =====");                                                  \
  } while (0)
#else
#define LOG_STAGE(name)                                                        \
  do {                                                                         \
  } while (0)
#endif
