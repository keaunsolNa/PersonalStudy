package com.greedy.section02.encapsulation.problem1;

public class Monster {
	
	String name;							// 몬스터 이름
	int hp;									// 몬스터 체력
	// 속성
	
	public void setHp(int hp) {
		if(hp >= 0) {						// 0또는 양수일 때
			System.out.println("양수값이 입력되어 몬스터의 체력을 입력한 값으로 변경합니다. ");
			this.hp = hp;					// monster4가 호출하는 순간, this.는 monster4가 된다.
		} else {							// 음수일 때
			System.out.println("0보다 작거나 같은 값이 입력되어 몬스터의 체력을 0으로 변경합니다.");
			this.hp = 0;
		}
		
		/*
		 * 여기에서의 this 는 두 가지 의미를 지닌다.
		 * 1. 매개변수인 int hp와 필드의 hp를 구분하기 위한 용도
		 * 2. 메소드를 호출할 당시 접근할 때 썼던 인스턴스를 의미하는 용도.
		 */
	}
	// 기능
	
}
