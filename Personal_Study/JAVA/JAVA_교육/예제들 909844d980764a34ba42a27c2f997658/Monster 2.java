package com.greedy.section02.encapsulation.problem3;

public class Monster {
	
	/* 수정 전 */
//	String name;
//	int hp;
	
	/* 수정 후 */
	String kinds;
	int hp;
	
	public void setInfo(String info) {
//		this.name = info;
		this.kinds = info;
	}
	
	public void setHp(int hp) {
		if(hp >= 0) {
			this.hp = hp;
		} else {
			this.hp = 0;
		}
	}
}
