![OpenPNP Logo](https://raw.githubusercontent.com/openpnp/openpnp-logo/develop/logo_small.png)

# OpenPnP

> **This is a fork of OpenPnP, not the official OpenPnP release.**
> It adds Photon feeder improvements made for a LumenPnP and is maintained separately at
> [MattStep90/OpenPNP-PhotonRewrite](https://github.com/MattStep90/OpenPNP-PhotonRewrite).
> It is not supported by the OpenPnP project, so please don't report its issues upstream.
> For the official software, see [openpnp/openpnp](https://github.com/openpnp/openpnp).

## What this fork adds

Branch `feature/photon-feeder-record`, based on OpenPnP `main` at 5bd404c (November 2026):

- **Reel record in the feeder:** part, count, tape width, pitch, low-parts warning and rotation in tape are stored in the feeder's flash and read back by OpenPnP.
- **Scan Part:** load a reel from its label (ECIA 2D or 1D codes) or a typed part number.
- **Pin 1 Quadrant picker:** sets the rotation in tape from the datasheet's EIA-481 quadrant.
- **Auto Setup:** teach the first and last slot of each rail, then every feeder's pick point is measured from its sprocket holes. Optional pick height probing, empty pocket check and vacuum level learning.
- **Firmware updates over the bus:** one feeder or all feeders, without an FTDI cable.
- **Drive calibration, Fix Lost Slots, failed-pick recheck and check-every-pick.**
- **Faster jobs:** feeders of a cycle advance together, with an optional feed right after each pick.
- **Footprint overlay** on the top camera and a Feeders list sorted by slot.

Most of these need the rewritten Photon feeder firmware (PhotonFeeder_F031) with the feeder record
commands. A machine.xml saved by this fork may not load in stock OpenPnP, so keep a backup.

The rest of this README is the original OpenPnP README.

Open Source SMT Pick and Place Hardware and Software

## Introduction

OpenPnP is a project to create the plans, prototype and software for a completely Open Source SMT
pick and place machine that anyone can afford. I believe that with the ubiquity of cheap, precise
motion control hardware, some ingenuity and plenty of Open Source software it should be possible
to build and own a fully functional SMT pick and place machine for under $1000.

## Project Status

OpenPnP is stable and in wide use. It is still under heavy development and new features are added continuously. See the [Downloads](http://openpnp.org/downloads) page to get started.

If you would like to keep up with our progress you can
[Watch this project on GitHub](http://github.com/openpnp/openpnp), check out
[our Twitter](http://twitter.com/openpnp), [join the discussion group](http://groups.google.com/group/openpnp),
or come chat with us on [Discord](https://discord.gg/EmsrFVx).

## Contributing

![Build Status](https://github.com/openpnp/openpnp/workflows/Build%20and%20Deploy%20OpenPnP/badge.svg)
[![Help Wanted](https://img.shields.io/github/issues-raw/openpnp/openpnp/help-wanted.svg?label=help-wanted&colorB=5319e7)](https://github.com/openpnp/openpnp/labels/help-wanted)
[![Bugs](https://img.shields.io/github/issues-raw/openpnp/openpnp/bug.svg?label=bugs&colorB=D9472F)](https://github.com/openpnp/openpnp/labels/bug)
[![Feature Requests](https://img.shields.io/github/issues-raw/openpnp/openpnp/feature-request.svg?label=feature-requests&colorB=bfd4f2)](https://github.com/openpnp/openpnp/labels/feature-request)
[![Enhancements](https://img.shields.io/github/issues-raw/openpnp/openpnp/enhancement.svg?label=enhancements&colorB=0052cc)](https://github.com/openpnp/openpnp/labels/enhancement)


Before starting work on a pull request, please read: https://github.com/openpnp/openpnp/wiki/Developers-Guide#contributing

Summary of guidelines:

* One pull request per issue.
* Describe the change.
* Follow the coding style.
* Include tests and documentation.
* Think of the big picture.

## Thanks

Many thanks to ej-technologies for providing a complimentary license of install4j. install4j
creates high quality, professional installers for Java applications.

More information at http://www.ej-technologies.com/products/install4j/overview.html.
