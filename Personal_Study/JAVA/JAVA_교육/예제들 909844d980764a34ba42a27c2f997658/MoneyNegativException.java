package com.greedy.section02.userexception.exception;

public class MoneyNegativException extends Exception{

	public MoneyNegativException() {}
	
	public MoneyNegativException(String message) {
		super(message);
	}
}
