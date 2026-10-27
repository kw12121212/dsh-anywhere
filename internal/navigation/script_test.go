package navigation

import (
	"strings"
	"testing"
)

func TestScriptUsesHandlerPrefix(t *testing.T) {
	if !strings.Contains(Script, `"`+OpenExternalPrefix+`"`) {
		t.Errorf("Script does not send messages with prefix %q", OpenExternalPrefix)
	}
	for _, hook := range []string{`addEventListener("click"`, `addEventListener("auxclick"`, "window.open ="} {
		if !strings.Contains(Script, hook) {
			t.Errorf("Script lacks %s", hook)
		}
	}
}
