"""The review desk for the Windows window, served straight off app/ui.

    python preview/serve.py [port] [--ui FOLDER]

    port    default 8766, this machine only
    --ui    the app/ui folder to show; default the one beside preview/.
            Point it at another checkout to review work that lives there,
            e.g. the main folder while a redesign is on its branch.

    /               preview/shell.html: the section index and the window frame
    /app/...        app/ui as it is on disk, with preview/mock.js loaded
                    before app.js in place of the Python side

Nothing is copied and nothing in app/ui is changed: an edit to app/ui or to
preview/ shows on the next reload. Nothing here connects anywhere either -
every answer the window gets is sample data from mock.js.
"""

import http.server
import json
import mimetypes
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(os.path.dirname(HERE), 'app', 'ui')

HOOK = '<script src="fuse.js"></script>'
MOCK = ('<!-- preview only: a stand-in for the Python side, see '
        'preview/mock.js -->\n<script src="mock.js"></script>\n')

PAGE = """<!doctype html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>
:root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}
body{margin:0;font:14px system-ui,sans-serif}img{max-width:100%}[hidden]{display:none!important}
</style>
</head>
<body>
{body}
</body>
</html>
"""


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def newest(*folders):
    """The latest change time of any file under these folders."""
    latest = 0.0
    for folder in folders:
        for base, _, names in os.walk(folder):
            for name in names:
                try:
                    latest = max(latest, os.path.getmtime(os.path.join(base, name)))
                except OSError:
                    pass
    return latest


def stamps():
    """What the desk polls to reload by itself: the window (app/ui and the
    stand-in backend) and the desk page are told apart, so an edit to the
    app reloads only the window and keeps the section you were on."""
    return {'app': max(newest(UI),
                       os.path.getmtime(os.path.join(HERE, 'mock.js'))),
            'desk': os.path.getmtime(os.path.join(HERE, 'shell.html'))}


class Desk(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=UI, **kwargs)

    def end_headers(self):
        # Always the file as it is now: the point is to see an edit at once.
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def send_text(self, text, kind):
        body = text.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def do_GET(self):
        path = self.path.split('?', 1)[0].split('#', 1)[0]
        if path in ('/', '/index.html'):
            shell = read(os.path.join(HERE, 'shell.html'))
            return self.send_text(PAGE.replace('{body}', shell),
                                  'text/html; charset=utf-8')
        if path in ('/app', '/app/', '/app/index.html'):
            html = read(os.path.join(UI, 'index.html'))
            if html.count(HOOK) != 1:
                return self.send_error(500, f'expected one {HOOK} in app/ui/index.html')
            return self.send_text(html.replace(HOOK, MOCK + HOOK),
                                  'text/html; charset=utf-8')
        if path == '/app/mock.js':
            return self.send_text(read(os.path.join(HERE, 'mock.js')),
                                  'text/javascript; charset=utf-8')
        if path == '/__live':
            return self.send_text(json.dumps(stamps()),
                                  'application/json; charset=utf-8')
        if path == '/favicon.ico':
            with open(os.path.join(os.path.dirname(UI), 'assets', 'app.ico'), 'rb') as f:
                icon = f.read()
            self.send_response(200)
            self.send_header('Content-Type', 'image/x-icon')
            self.send_header('Content-Length', str(len(icon)))
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(icon)
            return None
        if path.startswith('/app/'):
            # The rest is app/ui itself; the parent class keeps it inside.
            self.path = self.path[len('/app'):]
            return super().do_GET()
        if path.startswith('/proto/'):
            # Scene prototypes (preview/anubis/...), before anything is in the app.
            base = os.path.realpath(os.path.join(HERE, 'anubis'))
            file = os.path.realpath(os.path.join(base, path[len('/proto/'):]))
            if not file.startswith(base + os.sep) or not os.path.isfile(file):
                return self.send_error(404)
            with open(file, 'rb') as f:
                body = f.read()
            self.send_response(200)
            self.send_header('Content-Type', mimetypes.guess_type(file)[0] or 'application/octet-stream')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(body)
            return None
        return self.send_error(404)

    do_HEAD = do_GET

    def log_message(self, fmt, *args):
        if args and str(args[1])[:1] in '45':      # only what went wrong
            super().log_message(fmt, *args)


def main():
    global UI
    args = sys.argv[1:]
    if '--ui' in args:
        at = args.index('--ui')
        UI = os.path.abspath(args[at + 1])
        del args[at:at + 2]
    if not os.path.isfile(os.path.join(UI, 'index.html')):
        sys.exit(f'no index.html in {UI}')
    port = int(args[0]) if args else 8766
    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Desk)
    print(f'Mirage review desk on http://127.0.0.1:{port}/ showing {UI}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
