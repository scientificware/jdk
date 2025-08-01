/*
 * Copyright (c) 1997, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "memory/allocation.hpp"
#include "runtime/os.hpp"
#include "utilities/checkedCast.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/utf8.hpp"

// Assume the utf8 string is in legal form and has been
// checked in the class file parser/format checker.
template<typename T> char* UTF8::next(const char* str, T* value) {
  unsigned const char *ptr = (const unsigned char *)str;
  unsigned char ch, ch2, ch3;
  int length = -1;              /* bad length */
  jchar result;
  switch ((ch = ptr[0]) >> 4) {
    default:
    result = ch;
    length = 1;
    break;

  case 0x8: case 0x9: case 0xA: case 0xB: case 0xF:
    /* Shouldn't happen. */
    break;

  case 0xC: case 0xD:
    /* 110xxxxx  10xxxxxx */
    if (((ch2 = ptr[1]) & 0xC0) == 0x80) {
      unsigned char high_five = ch & 0x1F;
      unsigned char low_six = ch2 & 0x3F;
      result = (high_five << 6) + low_six;
      length = 2;
      break;
    }
    break;

  case 0xE:
    /* 1110xxxx 10xxxxxx 10xxxxxx */
    if (((ch2 = ptr[1]) & 0xC0) == 0x80) {
      if (((ch3 = ptr[2]) & 0xC0) == 0x80) {
        unsigned char high_four = ch & 0x0f;
        unsigned char mid_six = ch2 & 0x3f;
        unsigned char low_six = ch3 & 0x3f;
        result = (((high_four << 6) + mid_six) << 6) + low_six;
        length = 3;
      }
    }
    break;
  } /* end of switch */

  if (length <= 0) {
    *value = (T)ptr[0];    /* default bad result; */
    return (char*)(ptr + 1); // make progress somehow
  }

  *value = (T)result;

  // The assert is correct but the .class file is wrong
  // assert(UNICODE::utf8_size(result) == length, "checking reverse computation");
  return (char *)(ptr + length);
}

char* UTF8::next_character(const char* str, jint* value) {
  unsigned const char *ptr = (const unsigned char *)str;
  /* See if it's legal supplementary character:
     11101101 1010xxxx 10xxxxxx 11101101 1011xxxx 10xxxxxx */
  if (is_supplementary_character(ptr)) {
    *value = get_supplementary_character(ptr);
    return (char *)(ptr + 6);
  }
  jchar result;
  char* next_ch = next(str, &result);
  *value = result;
  return next_ch;
}

// The number of unicode characters in a utf8 sequence can be easily
// determined by noting that bytes of the form 10xxxxxx are part of
// a 2 or 3-byte multi-byte sequence, all others are either characters
// themselves or else the start of a multi-byte character.

// Calculate the unicode length of a utf8 string of known size
// by counting bytes of the form 10xxxxxx and deducting this count
// from the total byte count.  The utf8 string must be in
// legal form which has been verified in the format checker.
int UTF8::unicode_length(const char* str, size_t len, bool& is_latin1, bool& has_multibyte) {
  size_t num_chars = len;
  has_multibyte = false;
  is_latin1 = true;
  unsigned char prev = 0;
  for (size_t i = 0; i < len; i++) {
    unsigned char c = str[i];
    if ((c & 0xC0) == 0x80) {
      // Multibyte, check if valid latin1 character.
      has_multibyte = true;
      if (prev > 0xC3) {
        is_latin1 = false;
      }
      --num_chars;
    }
    prev = c;
  }
  return checked_cast<int>(num_chars);
}

// Calculate the unicode length of a nul-terminated utf8 string
// by counting bytes of the utf8 string except those in the form
// 10xxxxxx. The utf8 string must be in legal form and has been
// verified in the format checker.
int UTF8::unicode_length(const char* str, bool& is_latin1, bool& has_multibyte) {
  int num_chars = 0;
  has_multibyte = false;
  is_latin1 = true;
  unsigned char prev = 0;
  for (const char* p = str; *p; p++) {
    unsigned char c = (*p);
    if ((c & 0xC0) == 0x80) {
      // Multibyte, check if valid latin1 character.
      has_multibyte = true;
      if (prev > 0xC3) {
        is_latin1 = false;
      }
    } else {
      num_chars++;
    }
    prev = c;
  }
  return num_chars;
}

// Writes a jchar as utf8 and returns the end
static u_char* utf8_write(u_char* base, jchar ch) {
  if ((ch != 0) && (ch <=0x7f)) {
    base[0] = (u_char) ch;
    return base + 1;
  }

  if (ch <= 0x7FF) {
    /* 11 bits or less. */
    unsigned char high_five = ch >> 6;
    unsigned char low_six = ch & 0x3F;
    base[0] = high_five | 0xC0; /* 110xxxxx */
    base[1] = low_six | 0x80;   /* 10xxxxxx */
    return base + 2;
  }
  /* possibly full 16 bits. */
  char high_four = ch >> 12;
  char mid_six = (ch >> 6) & 0x3F;
  char low_six = ch & 0x3f;
  base[0] = high_four | 0xE0; /* 1110xxxx */
  base[1] = mid_six | 0x80;   /* 10xxxxxx */
  base[2] = low_six | 0x80;   /* 10xxxxxx */
  return base + 3;
}

template<typename T> void UTF8::convert_to_unicode(const char* utf8_str, T* unicode_str, int unicode_length) {
  unsigned char ch;
  const char *ptr = utf8_str;
  int index = 0;

  /* ASCII case loop optimization */
  for (; index < unicode_length; index++) {
    if((ch = ptr[0]) > 0x7F) { break; }
    unicode_str[index] = (T)ch;
    ptr = (const char *)(ptr + 1);
  }

  for (; index < unicode_length; index++) {
    ptr = UTF8::next(ptr, &unicode_str[index]);
  }
}

// Explicit instantiation for all supported string types.
template char* UTF8::next<jchar>(const char* str, jchar* value);
template char* UTF8::next<jbyte>(const char* str, jbyte* value);
template void UTF8::convert_to_unicode<jchar>(const char* utf8_str, jchar* unicode_str, int unicode_length);
template void UTF8::convert_to_unicode<jbyte>(const char* utf8_str, jbyte* unicode_str, int unicode_length);

// returns the quoted ascii length of a 0-terminated utf8 string
size_t UTF8::quoted_ascii_length(const char* utf8_str, size_t utf8_length) {
  const char *ptr = utf8_str;
  const char* end = ptr + utf8_length;
  size_t result = 0;
  while (ptr < end) {
    jchar c;
    ptr = UTF8::next(ptr, &c);
    if (c >= 32 && c < 127) {
      result++;
    } else {
      result += 6;
    }
  }
  return result;
}

// converts a utf8 string to quoted ascii
void UTF8::as_quoted_ascii(const char* utf8_str, size_t utf8_length, char* buf, size_t buflen) {
  const char *ptr = utf8_str;
  const char *utf8_end = ptr + utf8_length;
  char* p = buf;
  char* end = buf + buflen;
  while (ptr < utf8_end) {
    jchar c;
    ptr = UTF8::next(ptr, &c);
    if (c >= 32 && c < 127) {
      if (p + 1 >= end) break;      // string is truncated
      *p++ = (char)c;
    } else {
      if (p + 6 >= end) break;      // string is truncated
      os::snprintf_checked(p, 7, "\\u%04x", c);  // counting terminating zero in
      p += 6;
    }
  }
  assert(p < end, "sanity");
  *p = '\0';
}

#ifndef PRODUCT
// converts a quoted ascii string back to utf8
// no longer used, but could be useful to test output of UTF8::as_quoted_ascii
const char* UTF8::from_quoted_ascii(const char* quoted_ascii_str) {
  const char *ptr = quoted_ascii_str;
  char* result = nullptr;
  while (*ptr != '\0') {
    char c = *ptr;
    if (c < 32 || c >= 127) break;
  }
  if (*ptr == '\0') {
    // nothing to do so return original string
    return quoted_ascii_str;
  }
  // everything up to this point was ok.
  size_t length = ptr - quoted_ascii_str;
  char* buffer = nullptr;
  for (int round = 0; round < 2; round++) {
    while (*ptr != '\0') {
      if (*ptr != '\\') {
        if (buffer != nullptr) {
          buffer[length] = *ptr;
        }
        length++;
      } else {
        switch (ptr[1]) {
          case 'u': {
            ptr += 2;
            jchar value=0;
            for (int i=0; i<4; i++) {
              char c = *ptr++;
              switch (c) {
                case '0': case '1': case '2': case '3': case '4':
                case '5': case '6': case '7': case '8': case '9':
                  value = (value << 4) + c - '0';
                  break;
                case 'a': case 'b': case 'c':
                case 'd': case 'e': case 'f':
                  value = (value << 4) + 10 + c - 'a';
                  break;
                case 'A': case 'B': case 'C':
                case 'D': case 'E': case 'F':
                  value = (value << 4) + 10 + c - 'A';
                  break;
                default:
                  ShouldNotReachHere();
              }
            }
            if (buffer == nullptr) {
              char utf8_buffer[4];
              char* next = (char*)utf8_write((u_char*)utf8_buffer, value);
              length += next - utf8_buffer;
            } else {
              char* next = (char*)utf8_write((u_char*)&buffer[length], value);
              length += next - &buffer[length];
            }
            break;
          }
          case 't': if (buffer != nullptr) buffer[length] = '\t'; ptr += 2; length++; break;
          case 'n': if (buffer != nullptr) buffer[length] = '\n'; ptr += 2; length++; break;
          case 'r': if (buffer != nullptr) buffer[length] = '\r'; ptr += 2; length++; break;
          case 'f': if (buffer != nullptr) buffer[length] = '\f'; ptr += 2; length++; break;
          default:
            ShouldNotReachHere();
        }
      }
    }
    if (round == 0) {
      buffer = NEW_RESOURCE_ARRAY(char, length + 1);
      ptr = quoted_ascii_str;
    } else {
      buffer[length] = '\0';
    }
  }
  return buffer;
}
#endif // !PRODUCT

bool UTF8::equal(const jbyte* base1, int length1, const jbyte* base2, int length2) {
  // Length must be the same
  if (length1 != length2) return false;
  for (int i = 0; i < length1; i++) {
    if (base1[i] != base2[i]) return false;
  }
  return true;
}

bool UTF8::is_supplementary_character(const unsigned char* str) {
  return ((str[0] & 0xFF) == 0xED) && ((str[1] & 0xF0) == 0xA0) && ((str[2] & 0xC0) == 0x80)
      && ((str[3] & 0xFF) == 0xED) && ((str[4] & 0xF0) == 0xB0) && ((str[5] & 0xC0) == 0x80);
}

jint UTF8::get_supplementary_character(const unsigned char* str) {
  return 0x10000 + ((str[1] & 0x0f) << 16) + ((str[2] & 0x3f) << 10)
                 + ((str[4] & 0x0f) << 6)  + (str[5] & 0x3f);
}

bool UTF8::is_legal_utf8(const unsigned char* buffer, size_t length,
                         bool version_leq_47) {
  size_t i = 0;
  size_t count = length >> 2;
  for (size_t k = 0; k < count; k++) {
    unsigned char b0 = buffer[i];
    unsigned char b1 = buffer[i+1];
    unsigned char b2 = buffer[i+2];
    unsigned char b3 = buffer[i+3];
    // For an unsigned char v,
    // (v | v - 1) is < 128 (highest bit 0) for 0 < v < 128;
    // (v | v - 1) is >= 128 (highest bit 1) for v == 0 or v >= 128.
    unsigned char res = b0 | (b0 - 1) |
                        b1 | (b1 - 1) |
                        b2 | (b2 - 1) |
                        b3 | (b3 - 1);
    if (res >= 128) break;
    i += 4;
  }
  for(; i < length; i++) {
    unsigned short c;
    // no embedded zeros
    if (buffer[i] == 0) return false;
    if(buffer[i] < 128) {
      continue;
    }
    if ((i + 5) < length) { // see if it's legal supplementary character
      if (UTF8::is_supplementary_character(&buffer[i])) {
        c = UTF8::get_supplementary_character(&buffer[i]);
        i += 5;
        continue;
      }
    }
    switch (buffer[i] >> 4) {
      default: break;
      case 0x8: case 0x9: case 0xA: case 0xB: case 0xF:
        return false;
      case 0xC: case 0xD:  // 110xxxxx  10xxxxxx
        c = (buffer[i] & 0x1F) << 6;
        i++;
        if ((i < length) && ((buffer[i] & 0xC0) == 0x80)) {
          c += buffer[i] & 0x3F;
          if (version_leq_47 || c == 0 || c >= 0x80) {
            break;
          }
        }
        return false;
      case 0xE:  // 1110xxxx 10xxxxxx 10xxxxxx
        c = (buffer[i] & 0xF) << 12;
        i += 2;
        if ((i < length) && ((buffer[i-1] & 0xC0) == 0x80) && ((buffer[i] & 0xC0) == 0x80)) {
          c += ((buffer[i-1] & 0x3F) << 6) + (buffer[i] & 0x3F);
          if (version_leq_47 || c >= 0x800) {
            break;
          }
        }
        return false;
    }  // end of switch
  } // end of for
  return true;
}

// Return true if `b` could be the starting byte of an encoded 2,3 or 6
// byte sequence.
static bool is_starting_byte(unsigned char b) {
  return b >= 0xC0 && b <= 0xEF;
}

// Takes an incoming buffer that was valid UTF-8, but which has been truncated such that
// the last encoding may be partial, and returns the same buffer with a NUL-terminator
// inserted such that any partial encoding has gone.
// Note: if the incoming buffer is already valid then we may still drop the last encoding.
// To avoid that the caller can choose to check for validity first.
// The incoming buffer is still expected to be NUL-terminated.
// The incoming buffer is expected to be a realistic size - we assert if it is too small.
void UTF8::truncate_to_legal_utf8(unsigned char* buffer, size_t length) {
  assert(length > 5, "invalid length");
  assert(buffer[length - 1] == '\0', "Buffer should be NUL-terminated");

  if (buffer[length - 2] < 128) {  // valid "ascii" - common case
    return;
  }

  // Modified UTF-8 encodes characters in sequences of 1, 2, 3 or 6 bytes.
  // The last byte is invalid if it is:
  // - the 1st byte of a 2, 3 or 6 byte sequence
  //     0b110xxxxx
  //     0b1110xxxx
  //     0b11101101
  // - the 2nd byte of a 3 or 6 byte sequence
  //     0b10xxxxxx
  //     0b1010xxxx
  // - the 3rd, 4th or 5th byte of a 6 byte sequence
  //     0b10xxxxxx
  //     0b11101101
  //     0b1011xxxx
  //
  // Rather than checking all possible situations we simplify things noting that as we have already
  // got a truncated string, then dropping one more character is not significant. So we work from the
  // end of the buffer looking for the first byte that can be the starting byte of a UTF-8 encoded sequence,
  // then we insert NUL at that location to terminate the buffer. There is an added complexity with 6 byte
  // encodings as the first and fourth bytes are the same and overlap with the 3 byte encoding.

  for (size_t index = length - 2; index > 0; index--) {
    if (is_starting_byte(buffer[index])) {
      if (buffer[index] == 0xED) {
        // Could be first byte of 3 or 6, or fourth byte of 6.
        // If fourth the previous three bytes will encode a high
        // surrogate value in the range EDA080 to EDAFBF. We only
        // need to check for EDA to establish this as the "missing"
        // values in EDAxxx would not be valid 3 byte encodings.
        if (index >= 3 &&
            (buffer[index - 3] == 0xED) &&
            ((buffer[index - 2] & 0xF0) == 0xA0)) {
          assert(buffer[index - 1] >= 0x80 && buffer[index - 1] <= 0xBF, "sanity check");
          // It was fourth byte so truncate 3 bytes earlier
          index -= 3;
        }
      }
      buffer[index] = '\0';
      break;
    }
  }
}

//-------------------------------------------------------------------------------------

bool UNICODE::is_latin1(jchar c) {
  return (c <= 0x00FF);
}

bool UNICODE::is_latin1(const jchar* base, int length) {
  for (int index = 0; index < length; index++) {
    if (base[index] > 0x00FF) {
      return false;
    }
  }
  return true;
}

size_t UNICODE::utf8_size(jchar c) {
  if ((0x0001 <= c) && (c <= 0x007F)) {
    // ASCII character
    return 1;
  } else  if (c <= 0x07FF) {
    return 2;
  } else {
    return 3;
  }
}

size_t UNICODE::utf8_size(jbyte c) {
  if (c >= 0x01) {
    // ASCII character. Check is equivalent to
    // (0x01 <= c) && (c <= 0x7F) because c is signed.
    return 1;
  } else {
    // Non-ASCII character or 0x00 which needs to be
    // two-byte encoded as 0xC080 in modified UTF-8.
    return 2;
  }
}

template<typename T>
size_t UNICODE::utf8_length(const T* base, int length) {
  size_t result = 0;
  for (int index = 0; index < length; index++) {
    result += utf8_size(base[index]);
  }
  return result;
}

template<typename T>
int UNICODE::utf8_length_as_int(const T* base, int length) {
  size_t result = 0;
  for (int index = 0; index < length; index++) {
    T c = base[index];
    size_t sz = utf8_size(c);
    // If the length is > INT_MAX-1 we truncate at a completed
    // modified-UTF8 encoding. This allows for +1 to be added
    // by the caller for NUL-termination, without overflow.
    if (result + sz > INT_MAX-1) {
      break;
    }
    result += sz;
  }
  return checked_cast<int>(result);
}

template<typename T>
char* UNICODE::as_utf8(const T* base, size_t& length) {
  // Incoming length must be <= INT_MAX
  size_t utf8_len = utf8_length(base, static_cast<int>(length));
  u_char* buf = NEW_RESOURCE_ARRAY(u_char, utf8_len + 1);
  char* result = as_utf8(base, static_cast<int>(length), (char*) buf, utf8_len + 1);
  assert(strlen(result) == utf8_len, "length prediction must be correct");
  // Set outgoing string length to uft8 length
  length = utf8_len;
  return (char*) result;
}

char* UNICODE::as_utf8(const jchar* base, int length, char* buf, size_t buflen) {
  assert(buflen > 0, "zero length output buffer");
  u_char* p = (u_char*)buf;
  for (int index = 0; index < length; index++) {
    jchar c = base[index];
    size_t sz = utf8_size(c);
    if (sz >= buflen) break; // string is truncated
    buflen -= sz;
    p = utf8_write(p, c);
  }
  *p = '\0';
  return buf;
}

char* UNICODE::as_utf8(const jbyte* base, int length, char* buf, size_t buflen) {
  assert(buflen > 0, "zero length output buffer");
  u_char* p = (u_char*)buf;
  for (int index = 0; index < length; index++) {
    jbyte c = base[index];
    size_t sz = utf8_size(c);
    if (sz >= buflen) break; // string is truncated
    buflen -= sz;
    if (sz == 1) {
      // Copy ASCII characters (UTF-8 is ASCII compatible)
      *p++ = c;
    } else {
      assert(sz == 2, "must be!");
      // Non-ASCII character or 0x00 which should
      // be encoded as 0xC080 in "modified" UTF8.
      p = utf8_write(p, ((jchar) c) & 0xff);
    }
  }
  *p = '\0';
  return buf;
}

void UNICODE::convert_to_utf8(const jchar* base, int length, char* utf8_buffer) {
  for(int index = 0; index < length; index++) {
    utf8_buffer = (char*)utf8_write((u_char*)utf8_buffer, base[index]);
  }
  *utf8_buffer = '\0';
}

// returns the quoted ascii length of a unicode string
template<typename T>
size_t UNICODE::quoted_ascii_length(const T* base, int length) {
  size_t result = 0;
  for (int i = 0; i < length; i++) {
    T c = base[i];
    if (c >= 32 && c < 127) {
      result++;
    } else {
      result += 6;
    }
  }
  return result;
}

// converts a unicode string to quoted ascii
template<typename T>
void UNICODE::as_quoted_ascii(const T* base, int length, char* buf, size_t buflen) {
  char* p = buf;
  char* end = buf + buflen;
  for (int index = 0; index < length; index++) {
    T c = base[index];
    if (c >= 32 && c < 127) {
      if (p + 1 >= end) break;      // string is truncated
      *p++ = (char)c;
    } else {
      if (p + 6 >= end) break;      // string is truncated
      os::snprintf_checked(p, 7, "\\u%04x", c);
      p += 6;
    }
  }
  *p = '\0';
}

// Explicit instantiation for all supported types.
template size_t UNICODE::utf8_length(const jbyte* base, int length);
template size_t UNICODE::utf8_length(const jchar* base, int length);
template int UNICODE::utf8_length_as_int(const jbyte* base, int length);
template int UNICODE::utf8_length_as_int(const jchar* base, int length);
template char* UNICODE::as_utf8(const jbyte* base, size_t& length);
template char* UNICODE::as_utf8(const jchar* base, size_t& length);
template size_t UNICODE::quoted_ascii_length<jbyte>(const jbyte* base, int length);
template size_t UNICODE::quoted_ascii_length<jchar>(const jchar* base, int length);
template void UNICODE::as_quoted_ascii<jbyte>(const jbyte* base, int length, char* buf, size_t buflen);
template void UNICODE::as_quoted_ascii<jchar>(const jchar* base, int length, char* buf, size_t buflen);
