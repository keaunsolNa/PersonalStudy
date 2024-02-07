package com.greedy.section01.method;

public class Calculator {

	/* non-static */
	public int minNumberOf(int first, int second) {
		int result = (first > second) ? second : first;
		return result;		
	}
		
	/* static */
	public static int maxNumberOf(int first, int second) {
		return (first > second) ? first : second;	
	}
	
}

	