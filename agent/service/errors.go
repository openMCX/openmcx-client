//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package main

import (
	"errors"
	"fmt"
)

// The error codes of the tools: stable names an agent can act on, part of the API contract. The message beside
// a code is for people and may change.
const (
	codeInvalidArgument = "invalid_argument" // an argument is missing, of the wrong type or not acceptable
	codeUnknownTool     = "unknown_tool"
	codeNotFound        = "not_found"        // no such message, file offer or outbox file
	codeNotAllowed      = "not_allowed"      // the configuration does not allow this recipient
	codeRateLimited     = "rate_limited"     // too many sends in the last minute; try again later
	codeAliasInactive   = "alias_inactive"   // the agent's functional alias is not active, so it may not send
	codeNotReady        = "engine_not_ready" // the engine is starting, not registered or not authorised for MCData
	codeEngineRefused   = "engine_refused"   // the engine refused the command (its client_error)
	codeSendFailed      = "send_failed"      // the network refused the message or the recipient never got it
	codeTransferFailed  = "transfer_failed"  // a file upload or download failed
	codeTimeout         = "timeout"          // the engine reported no outcome in time
	codeInternal        = "internal"
)

// toolError is an error with its code.
type toolError struct {
	code, message string
}

func (e *toolError) Error() string { return e.message }

func fail(code, format string, args ...any) error {
	return &toolError{code: code, message: fmt.Sprintf(format, args...)}
}

// errorCode is the code of err: its own, timeout for errTimeout, internal otherwise.
func errorCode(err error) string {
	var coded *toolError
	if errors.As(err, &coded) {
		return coded.code
	}
	if errors.Is(err, errTimeout) {
		return codeTimeout
	}
	return codeInternal
}

// exitStatus is the CLI's exit status for a code: 2 a usage error, 3 refused by the agent's policy (or its
// alias), 4 the engine not ready, 1 any other failure.
func exitStatus(code string) int {
	switch code {
	case codeInvalidArgument, codeUnknownTool:
		return 2
	case codeNotAllowed, codeRateLimited, codeAliasInactive:
		return 3
	case codeNotReady:
		return 4
	}
	return 1
}
