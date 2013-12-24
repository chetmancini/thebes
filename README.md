# Thebes

Thebes was one of the great cities of ancient Egypt, along the banks of one of the greatest rivers in teh world, the Nile. Similarly, this repository intends to be an architecture for leveraging a great river of realtime data.

Thebes has two components isolated for application and dependecy purposes. The api is the public input and will support configuration for a variety of queues. The processor is the backend component for the primary data processing by storm or cascalog.

Thebes attempts to be written in 100% Clojure.

## Installation

Install Leiningen, and then download directly from Github.

## Usage

The API

	$ cd thebes-api
	$ lein api

The Processor

	$ cd thebes-processor
    $ lein topology

## Options

## Examples

## Bugs

## License

Copyright © 2013 Chet Mancini

Distributed under the Eclipse Public License. No warranty of any kind is given or implied.
