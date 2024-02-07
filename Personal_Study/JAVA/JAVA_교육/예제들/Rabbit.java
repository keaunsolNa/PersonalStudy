package com.greedy.section01.polymorphism;

public class Rabbit extends Animal {
	
	/* Rabbit은 Animal을 상속 받았는데 토끼에 맞게 조금 구체화해서 행동을 정의해 보자. */
	@Override
	public void eat() {
		System.out.println("토끼가 풀을 뜯어 먹고 있습니다.");
	}
	
	@Override
	public void run() {
		System.out.println("토끼가 달려갑니다. 깡총~ 깡총~");

	}
	
	@Override
	public void cry() {
		System.out.println("토끼가 울음소리를 냅니다. 끼익~ 끼익~");

	}
	
	public void jump() {
		System.out.println("토끼가 점프합니다. 점프!!!");
	}
}
