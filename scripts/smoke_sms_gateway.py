#!/usr/bin/env python3
"""Smoke test Portal SMS gateway endpoints over local HTTP and WebSocket."""

import argparse
import asyncio
import json
import os
import sys
import urllib.error
import urllib.request
import uuid


def http_post(base_url, token, method, payload):
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/{method}",
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


async def websocket_call(url, token, method, params):
    try:
        import websockets
    except ImportError:
        return {"status": "skipped", "reason": "python package 'websockets' is not installed"}

    request_id = str(uuid.uuid4())
    separator = "&" if "?" in url else "?"
    async with websockets.connect(f"{url}{separator}token={token}") as ws:
        await ws.send(json.dumps({"id": request_id, "method": method, "params": params}))
        while True:
            raw = await asyncio.wait_for(ws.recv(), timeout=10)
            message = json.loads(raw)
            if message.get("id") == request_id:
                return message


def assert_ok(name, response):
    if response.get("status") != "success":
        raise AssertionError(f"{name} failed: {response}")
    print(f"ok: {name}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("PORTAL_HTTP_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--ws-url", default=os.environ.get("PORTAL_WS_URL", "ws://127.0.0.1:8081"))
    parser.add_argument("--token", default=os.environ.get("PORTAL_TOKEN"))
    parser.add_argument("--phone")
    parser.add_argument("--message", default="Mobilerun SMS gateway smoke test")
    parser.add_argument("--send", action="store_true", help="Actually call sms/send; requires a real SIM and phone number.")
    parser.add_argument("--require-websocket", action="store_true")
    args = parser.parse_args()

    if not args.token:
        print("PORTAL_TOKEN or --token is required", file=sys.stderr)
        return 2

    try:
        capabilities = http_post(args.base_url, args.token, "sms/capabilities", {})
        assert_ok("http sms/capabilities", capabilities)
        if not capabilities["result"].get("gateway_enabled"):
            raise AssertionError(
                "SMS gateway is disabled; enable it in Portal Settings or with "
                "adb shell content insert --uri content://com.mobilerun.portal/sms/gateway --bind enabled:b:true"
            )
        messages = http_post(args.base_url, args.token, "sms/messages/list", {"box": "inbox", "limit": 1})
        if messages.get("status") == "success":
            print("ok: http sms/messages/list")
        elif "READ_SMS" in messages.get("error", ""):
            print("ok: http sms/messages/list returned expected READ_SMS permission error")
        else:
            raise AssertionError(f"unexpected sms/messages/list response: {messages}")

        if args.send:
            if not args.phone:
                raise AssertionError("--send requires --phone")
            send = http_post(
                args.base_url,
                args.token,
                "sms/send",
                {"phone_number": args.phone, "message": args.message, "message_id": str(uuid.uuid4())},
            )
            assert_ok("http sms/send", send)
            message_id = send["result"]["message_id"]
            assert_ok("http sms/status", http_post(args.base_url, args.token, "sms/status", {"message_id": message_id}))

        ws_response = asyncio.run(websocket_call(args.ws_url, args.token, "sms/capabilities", {}))
        if ws_response.get("status") == "skipped":
            if args.require_websocket:
                raise AssertionError(ws_response["reason"])
            print(f"skip: websocket sms/capabilities ({ws_response['reason']})")
        else:
            assert_ok("websocket sms/capabilities", ws_response)
    except urllib.error.URLError as exc:
        print(f"HTTP connection failed: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"smoke failed: {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
