package com.greedy.section02.userexception.exception;

public class PriceNegativException extends Exception{

	public PriceNegativException() {}
	
	public PriceNegativException(String message) {
		super(message);
	}
}
