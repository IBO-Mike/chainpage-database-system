"""JSONL command-line adapter for the page-storage subsystem.

Example:
  echo '{"op":"storage_stats"}' | python storage_cli.py --root ./data
"""
import argparse
import json
import sys

from storage import StorageManager


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', default='./storage-data')
    parser.add_argument('--capacity', type=int, default=16)
    parser.add_argument('--policy', choices=['LRU', 'FIFO'], default='LRU')
    parser.add_argument('--no-log', action='store_true')
    args = parser.parse_args()

    store = StorageManager(args.root, capacity=args.capacity, policy=args.policy, log=not args.no_log)
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError as exc:
            response = {
                'ok': False,
                'error': {
                    'stage': 'PAGE', 'code': 'INVALID_JSON', 'message': str(exc),
                    'pageId': None, 'requestId': None, 'statementIndex': None,
                    'line': None, 'column': None,
                },
            }
        else:
            response = store.handle(request)
        print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
