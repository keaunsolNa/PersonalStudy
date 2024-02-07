package com.greedy.section01.list.run;

import java.util.Stack;

public class Application4 {
	public static void main(String[] args) {

		/* stack */
		/*
		 * stack은 리스트 계열 클래스의 Vector 클래스를 상속받아 구현하였다.
		 * 후입선출(LIFO - Last Input First Out) 방식의 자료구조라 불린다.
		 */
		
		/* STack 인스턴스 생성 */
		Stack<Integer> integerStack = new Stack<>();
		
		/*
		 * Stack에 값을 넣을 때는 push()메소드를 사용한다.
		 * add()도 가능하지만 가능한 push()를 사용하는 것이 좋다.
		 */
		integerStack.push(1);
		integerStack.push(2);
		integerStack.push(3);
		integerStack.push(4);
		integerStack.push(5);
		 
		System.out.println(integerStack);
		
		/* 스텍에서 요소를 찾을 때 search()를 이용할 수 있다. ( 맨 위에서부터 떨어진 거리만큼을 정수로 반환 */
		System.out.println(integerStack.search(5));				// 1을 반환
		
		/*
		 * Stack에서 값을 꺼내는 메소드는 크게 2가지로 볼 수 있다.
		 * peek() : 해당 스택의 가장 마지막에 있는 (상단에 있는) 요소 반환
		 * pop() : 해당 스택의 가장 마지막에 있는(상담에 있는) 요소 반환 후 제거
		 */
		System.out.println("peak() : " + integerStack.peek());
		System.out.println(integerStack);
		
		System.out.println("pop() : " + integerStack.pop());
		System.out.println("pop() : " + integerStack.pop());
		System.out.println("pop() : " + integerStack.pop());
		System.out.println("pop() : " + integerStack.pop());
		System.out.println("pop() : " + integerStack.pop());
//		System.out.println("pop() : " + integerStack.pop());			// EmptyStackException 발생
		System.out.println(integerStack);
	}

}
