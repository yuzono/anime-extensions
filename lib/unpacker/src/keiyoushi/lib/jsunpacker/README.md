# Kotlin Multiplatform Javascript Unpacker

[![Issues](https://img.shields.io/github/issues/DATL4G/JsUnpacker.svg?style=for-the-badge)](https://github.com/DATL4G/JsUnpacker/issues)
[![Stars](https://img.shields.io/github/stars/DATL4G/JsUnpacker.svg?style=for-the-badge)](https://github.com/DATL4G/JsUnpacker/stargazers)
[![Forks](https://img.shields.io/github/forks/DATL4G/JsUnpacker.svg?style=for-the-badge)](https://github.com/DATL4G/JsUnpacker/network/members)
[![Contributors](https://img.shields.io/github/contributors/DATL4G/JsUnpacker.svg?style=for-the-badge)](https://github.com/DATL4G/JsUnpacker/graphs/contributors)
[![License](https://img.shields.io/github/license/DATL4G/JsUnpacker.svg?style=for-the-badge)](https://github.com/DATL4G/JsUnpacker/blob/master/LICENSE)

This a rebuilt unpacker for JavaScript blocks that were packed with [Dean Edwards JavaScript's Packer](http://dean.edwards.name/packer/).

The project is based on [php-javascript-unpacker](https://github.com/rkaradas/php-javascript-unpacker).

## Installation

This project is available on maven central

```gradle
implementation("dev.datlag.jsunpacker:jsunpacker:1.0.1")
```

## Usage

The purpose of this script is to unpack automated data, e.g. site scraping, where you need to get the unpacked version of a packed JavaScript to parse out link or something like that.

**The UnpackUnitTest class provides some small examples and shows some limitations on how to use it.**

```kotlin
val isPacked = JsUnpacker.detect("some string here")
val unpacked = JsUnpacker.unpackAndCombine("simple example")

println(isPacked)
println(unpacked)
```
