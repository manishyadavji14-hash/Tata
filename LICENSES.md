# Third-Party Licenses

BitPerfect uses the following open-source libraries and specifications. All code in this project is original implementation based on public specifications.

## Test Dependencies

### Google Test (GTest)
- **License**: BSD 3-Clause
- **Usage**: Native C++ unit testing framework (test builds only, not included in release APK)
- **Source**: https://github.com/google/googletest
- **License Text**: https://github.com/google/googletest/blob/main/LICENSE

## Runtime Dependencies

### Kotlin Standard Library
- **License**: Apache License 2.0
- **Usage**: Core language runtime
- **Source**: https://github.com/JetBrains/kotlin
- **Copyright**: Copyright 2010-2024 JetBrains s.r.o.

### AndroidX Libraries
- **License**: Apache License 2.0
- **Usage**: Core Android compatibility and architecture components
- **Libraries used**:
  - androidx.core:core-ktx
  - androidx.appcompat:appcompat
  - androidx.lifecycle:lifecycle-runtime-ktx
  - androidx.lifecycle:lifecycle-viewmodel-compose
  - androidx.activity:activity-compose
  - androidx.media:media
- **Source**: https://github.com/androidx/androidx
- **Copyright**: Copyright The Android Open Source Project

### Jetpack Compose
- **License**: Apache License 2.0
- **Usage**: Modern declarative UI toolkit
- **Libraries used**:
  - androidx.compose.ui:ui
  - androidx.compose.material3:material3
  - androidx.compose.runtime:runtime
  - androidx.compose.foundation:foundation
  - androidx.navigation:navigation-compose
- **Source**: https://github.com/androidx/androidx
- **Copyright**: Copyright The Android Open Source Project

### Material 3 (Material Design 3)
- **License**: Apache License 2.0
- **Usage**: UI design system components
- **Source**: https://github.com/material-components/material-components-android
- **Copyright**: Copyright Google LLC

### Room Persistence Library
- **License**: Apache License 2.0
- **Usage**: SQLite database abstraction for music library
- **Libraries used**:
  - androidx.room:room-runtime
  - androidx.room:room-ktx
  - androidx.room:room-compiler (annotation processor)
- **Source**: https://github.com/androidx/androidx
- **Copyright**: Copyright The Android Open Source Project

### Kotlin Coroutines
- **License**: Apache License 2.0
- **Usage**: Asynchronous programming for I/O operations
- **Source**: https://github.com/Kotlin/kotlinx.coroutines
- **Copyright**: Copyright 2016-2024 JetBrains s.r.o.

## Specifications Used

### USB Audio Class Specifications
- **USB Audio Class 1.0**: Universal Serial Bus Device Class Definition for Audio Devices, Release 1.0
- **USB Audio Class 2.0**: Universal Serial Bus Device Class Definition for Audio Devices, Release 2.0
- **USB Audio Data Formats**: Audio Data Formats specification
- **Status**: Public specifications available from USB Implementers Forum (usb.org)
- **Usage**: Descriptor parsing, endpoint configuration, sample rate negotiation, isochronous transfer management

### DSD/DSF File Format
- **DSF File Format Specification**: Published by Sony
- **Status**: Public specification
- **Usage**: DSF file header parsing, DSD data extraction

### DoP (DSD over PCM) Standard
- **DoP open Standard**: Version 1.1
- **Published by**: dCS (Data Conversion Systems)
- **Status**: Open standard, freely implementable
- **Usage**: DSD-to-DoP frame encoding with marker bytes (0x05/0xFA alternation)

### WAV/RIFF Format
- **RIFF Resource Interchange File Format**: Microsoft/IBM specification
- **Status**: Public specification
- **Usage**: WAV file header parsing, PCM data extraction

### FLAC Format
- **Free Lossless Audio Codec**: Xiph.Org specification
- **Status**: Open format
- **Usage**: FLAC file header parsing (decoder implementation)

## Notes

- **No proprietary code**: This project does not use any proprietary code from commercial audio players (Neutron, USB Audio Player Pro, etc.)
- **No DRM/MQA**: No proprietary or patent-encumbered codecs are included
- **Original implementation**: All audio pipeline code (ring buffer, PCM engine, DoP encoder, USB transfer management, DSD transport) is original implementation based solely on the public specifications listed above
- **Test-only dependencies**: Google Test is used only in the standalone test build and is not included in the production Android APK

## Apache License 2.0 Summary

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## BSD 3-Clause License Summary (Google Test)

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.
```
