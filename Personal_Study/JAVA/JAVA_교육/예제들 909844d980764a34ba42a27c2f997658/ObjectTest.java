package com.greedy.section01.generic;

public class ObjectTest {
	private Object value;

	public ObjectTest(Object value) {
		super();
		this.value = value;
	}

	public ObjectTest() {
		super();
	}

	Object getValue() {
		return value;
	}

	void setValue(Object value) {
		this.value = value;
	}

}
