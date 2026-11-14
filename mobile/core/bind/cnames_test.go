package relay

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"strings"
	"testing"
)

// gomobile writes every exported function of this package into C, parameter
// names and all, and a name that is a C keyword stops the .aar from building
// - in CI, at the very end, after every architecture has compiled. A
// parameter called `auto` did exactly that (2026-11-14). Caught here instead,
// where `go test` runs on any machine.
var cKeywords = map[string]bool{
	"auto": true, "break": true, "case": true, "char": true, "const": true,
	"continue": true, "default": true, "do": true, "double": true, "else": true,
	"enum": true, "extern": true, "float": true, "for": true, "goto": true,
	"if": true, "inline": true, "int": true, "long": true, "register": true,
	"restrict": true, "return": true, "short": true, "signed": true,
	"sizeof": true, "static": true, "struct": true, "switch": true,
	"typedef": true, "union": true, "unsigned": true, "void": true,
	"volatile": true, "bool": true, "true": true, "false": true,
	"nullptr": true, "alignas": true, "alignof": true, "typeof": true,
	"env": true, // the JNIEnv parameter gomobile adds to every function
}

func TestExportedParametersAreNotCKeywords(t *testing.T) {
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	for _, e := range entries {
		name := e.Name()
		if !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		f, err := parser.ParseFile(fset, name, nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		for _, d := range f.Decls {
			fn, ok := d.(*ast.FuncDecl)
			if !ok || !fn.Name.IsExported() || fn.Type.Params == nil {
				continue
			}
			for _, field := range fn.Type.Params.List {
				for _, p := range field.Names {
					if cKeywords[p.Name] {
						t.Errorf("%s: %s has a parameter named %q, a C keyword gomobile cannot bind",
							fset.Position(p.Pos()), fn.Name.Name, p.Name)
					}
				}
			}
		}
	}
}
