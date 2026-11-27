package com.mealplanner.exception;

/**
 * Error payload returned for validation and runtime failures.
 */
public record ApiError(
		int status,
		String error,
		String message,
		String path,
		java.util.Map<String, String> fieldErrors) {
}
