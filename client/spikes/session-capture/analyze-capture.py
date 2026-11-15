#!/usr/bin/env python3
"""Summarizes an rd trace captured from JetBrains Client (see docs/split-protocol.md, "A recorded session").

Usage: analyze-capture.py <capture.log>

The capture is the client's idea.log (all rotated parts joined, oldest first) with
-Didea.log.trace.categories=protocol.SEND,protocol.RECV,protocol.INIT. Prints:
1. the connection timeline (INFO lines of the connection classes),
2. which side created each rd extension first, and whether both sides sent the same schema hash,
3. per top-level model: first use and SEND/RECV counts,
4. Fleet RPC message kinds and the most used service methods.
"""
import collections
import re
import sys

HEADER = re.compile(r'^\d{4}-\d\d-\d\d (\d\d:\d\d:\d\d,\d{3}) \[ *\d+\] +(\w+) - (\S+) - (.*)$')
EXT_CREATED = re.compile(
    r'signal `ThinClient.ProtocolExtCreated`.*?rName=(?:CodeWithMe-Wire-[\w-]+?\.|ThinClient\.)?([\w.\[\]$]+?), rdId=\S+, hash=(-?\d+)')
CONNECTION_CATEGORIES = re.compile(
    r'SecureWire|ConnectionFactory|TransportCreator|WireStorage|RemoteTrustManager|ConnectionState|ThinClientStarter')


def read_blocks(path: str) -> list[tuple[str, str, str, str]]:
    """Returns (time, level, category, full message including continuation lines) per log record."""
    if not path:
        raise SystemExit('usage: analyze-capture.py <capture.log>')
    text = open(path, errors='replace').read()
    blocks = []
    for block in re.split(r'\n(?=\d{4}-\d\d-\d\d )', text):
        first_line, _, rest = block.partition('\n')
        match = HEADER.match(first_line)
        if match:
            time, level, category, message = match.groups()
            blocks.append((time, level, category, message + ('\n' + rest if rest else '')))
    return blocks


def main(path: str) -> None:
    blocks = read_blocks(path)
    if not any(category.startswith('protocol.') for _, _, category, _ in blocks):
        raise SystemExit('no protocol.* trace lines: was idea.log.trace.categories set?')

    print('== Connection timeline')
    for time, level, category, message in blocks:
        if level in ('INFO', 'WARN', 'ERROR') and CONNECTION_CATEGORIES.search(category):
            print(time, level, category.split('.')[-1], message.split('\n')[0][:170])

    print('\n== Extensions: who created each first, and schema hash match')
    first_seen: dict[str, dict[str, tuple[str, str]]] = {}
    for time, _, category, message in blocks:
        if category in ('protocol.SEND', 'protocol.RECV'):
            match = EXT_CREATED.search(message.split('\n')[0])
            if match:
                name, hash_value = match.groups()
                first_seen.setdefault(name, {}).setdefault(category[-4:], (time, hash_value))
    for name, sides in sorted(first_seen.items(), key=lambda item: min(side[0] for side in item[1].values())):
        sent, received = sides.get('SEND'), sides.get('RECV')
        creator = 'client' if sent and (not received or sent[0] <= received[0]) else 'host'
        hashes = '-' if not (sent and received) else ('same' if sent[1] == received[1] else 'DIFFERENT')
        print(f'{min(side[0] for side in sides.values())} first={creator:6} hash={hashes:9} {name}')

    print('\n== Top-level models: first use, SEND/RECV counts')
    first_use: dict[str, str] = {}
    counts: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    for time, _, category, message in blocks:
        if category in ('protocol.SEND', 'protocol.RECV'):
            match = re.search(r'`ThinClient\.([A-Za-z]+)', message.split('\n')[0])
            if match:
                first_use.setdefault(match.group(1), time)
                counts[match.group(1)][category[-4:]] += 1
    for name in sorted(first_use, key=first_use.get):
        print(first_use[name], name, dict(counts[name]))

    print('\n== Fleet RPC')
    kinds: collections.Counter = collections.Counter()
    methods: collections.Counter = collections.Counter()
    for _, _, category, message in blocks:
        if 'FleetTransportMessage' not in message or not category.startswith('protocol.'):
            continue
        direction = category[-4:]
        kind = re.search(r'\\"type\\":\\"(\w+)', message)
        kinds[(direction, kind.group(1) if kind else 'unknown')] += 1
        for service, method in re.findall(r'\\"service\\":\\"([\w.$]+)\\",\\"method\\":\\"(\w+)', message):
            methods[f"{direction} {service.split('.')[-1]}.{method}"] += 1
    for (direction, kind), count in sorted(kinds.items()):
        print(direction, kind, count)
    for name, count in methods.most_common(25):
        print(count, name)
    print(len(methods), 'distinct service methods')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '')
