#!/usr/bin/env python3
"""Upload an immutable SBOM, scan, and wait for a bound, current PASS. Python 3.11+."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--project', required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--sbom', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--idempotency-key', default=str(uuid.uuid4()))
    args = parser.parse_args()
    token = os.environ['DEPRADAR_CI_TOKEN']
    if args.timeout <= 0:
        parser.error('--timeout must be positive')
    base = args.base_url.rstrip('/') + '/api/v1'

    def request(path, method='GET', body=None, content_type=None, key=None):
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}
        if content_type:
            headers['Content-Type'] = content_type
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # Never include request headers or the bearer token in diagnostics.
            raise RuntimeError(f'HTTP {error.code}: {error.read().decode("utf-8")}') from None

    raw = args.sbom.read_bytes()
    sbom_hash = hashlib.sha256(raw).hexdigest()
    boundary = 'depradar-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="tag"\r\n\r\n{args.tag}\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sbom.json"\r\n'
            'Content-Type: application/json\r\n\r\n').encode() + raw + f'\r\n--{boundary}--\r\n'.encode()
    release = request('/projects/' + urllib.parse.quote(args.project, safe='') + '/releases', 'POST', body,
                      'multipart/form-data; boundary=' + boundary, args.idempotency_key + ':import')
    if release['sbomHash'] != sbom_hash:
        raise RuntimeError('上传结果的 SBOM hash 与本地文件不符')
    release_id = release['id']
    scan = request('/releases/' + release_id + '/scans', 'POST', b'', key=args.idempotency_key + ':scan')
    scan_id = scan['id']
    print(f'扫描 {scan_id}：{args.base_url.rstrip("/")}/scans/{scan_id}', flush=True)
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        progress = request('/scans/' + scan_id)['scan']
        status = progress['status']
        if status in ('PARTIAL', 'FAILED', 'CANCELLED'):
            print(f'扫描未获得完整报告：{status}', file=sys.stderr)
            return 3
        if status == 'SUCCEEDED':
            query = urllib.parse.urlencode({'scanId': scan_id, 'sbomHash': sbom_hash})
            gate = request('/releases/' + release_id + '/gate?' + query)
            binding = gate['binding']
            expected = {'releaseId': release_id, 'sbomHash': sbom_hash, 'scanId': scan_id,
                        'reportHash': progress['reportHash'], 'policyVersion': progress['policyVersion']}
            if binding != expected:
                raise RuntimeError('门禁报告绑定不符')
            print(f'{gate["result"]}: {"; ".join(gate["reasons"])}', flush=True)
            if gate['result'] == 'PASS':
                valid_until = dt.datetime.fromisoformat(gate['validUntil'].replace('Z', '+00:00'))
                if valid_until <= dt.datetime.now(dt.timezone.utc):
                    raise RuntimeError('PASS 已过有效截止时间')
                return 0
            if gate['result'] == 'BLOCK':
                return 2
            if gate['result'] != 'INDETERMINATE':
                raise RuntimeError('未知门禁结果')
        time.sleep(min(5, max(0, deadline - time.monotonic())))
    print('等待扫描或人工批准超时，禁止继续发布。', file=sys.stderr)
    return 3


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, KeyError, OSError, ValueError) as error:
        print(f'DepRadar CI 失败：{error}', file=sys.stderr)
        sys.exit(3)
