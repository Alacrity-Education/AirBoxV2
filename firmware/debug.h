// AirBox V2 debug / bench configuration.
//
// This is the ONE file you edit while building and testing on the bench.
// Credentials stay in config.h, the pin map stays in pins.h. Neither of those
// needs to change to test hardware.
//
// HOW TO USE THIS FILE
//   1. While working on the bench, leave BENCH_MODE defined (uncommented). The
//      board then LOOPS instead of deep-sleeping, so the USB serial console
//      stays connected and you can watch readings update live.
//   2. Bring the subsystems up one at a time with the RUN_* switches below.
//      Suggested order: guard probe -> SEN66 -> power -> WiFi -> POST. Turn
//      WiFi on LAST, once every sensor reads correctly offline.
//   3. When the whole board works, comment BENCH_MODE out (put // in front of
//      it). The board then runs a single measurement cycle and deep-sleeps,
//      and every stage is forced on regardless of the RUN_* values.
//
// Every subsystem prints under its own tag, e.g. [I][sen66], so you can see at
// a glance which stage produced which line.
#pragma once
#include <Arduino.h>

//  BENCH MODE  ->  comment this one line out for a field deployment.
// when the BENCH_MODE const is not defined, FIELD_MODE is activated
// FIELD MODE active: runCycle() runs once per boot, then goToSleep() deep-sleeps.
// #define BENCH_MODE

//  Per-stage switches. Only used while BENCH_MODE is defined.
//  1 = build and run this stage, 0 = leave it out.
//  A stage set to 0 is removed from the build entirely, so an unfinished stage
//  you set to 0 cannot break compilation of the stages you are working on.
#ifdef BENCH_MODE
#define RUN_GUARD 1 // DS18B20 guard temperature probe
#define RUN_SEN66 1 // SEN66 air-quality sensor
#define RUN_POWER 1 // battery / solar ADC sampling
#define RUN_WIFI 1  // WiFi connect   <- ON for bench WiFi/upload bring-up
#define RUN_POST 1  // HTTPS upload   <- ON for bench WiFi/upload bring-up

// Pause between bench cycles, in seconds. Short so readings refresh quickly.
constexpr uint32_t BENCH_LOOP_DELAY_S = 5;
#else
// Field deployment: the whole pipeline always runs, no exceptions.
#define RUN_GUARD 1
#define RUN_SEN66 1
#define RUN_POWER 1
#define RUN_WIFI 1
#define RUN_POST 1
#endif

//  Serial log verbosity.
//    LOG_NONE  - silent
//    LOG_ERROR - failures only
//    LOG_WARN  - + warnings (skipped stages, suspicious readings)
//    LOG_INFO  - + normal per-stage results          (good default)
//    LOG_DEBUG - + raw values and per-attempt detail  (most verbose)
//  On the bench you want LOG_DEBUG so you can see raw ADC counts and raw
//  sensor integers. In the field LOG_INFO keeps the log readable and cheap.
#define LOG_NONE 0
#define LOG_ERROR 1
#define LOG_WARN 2
#define LOG_INFO 3
#define LOG_DEBUG 4

#ifdef BENCH_MODE
#define LOG_LEVEL LOG_DEBUG
#else
#define LOG_LEVEL LOG_INFO
#endif

//  Logging macros.
//  Each call takes a short tag and a printf-style message. A message string is
//  ALWAYS required, do not call a macro with the tag alone:
//      LOGI("guard", "temperature: %.2f C", t);   // correct
//      LOGI("guard");                              // will not compile
//  When a level is disabled the macro expands to nothing and its arguments are
//  not evaluated, so leaving debug prints in the code costs nothing in thefield
//  build.
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
