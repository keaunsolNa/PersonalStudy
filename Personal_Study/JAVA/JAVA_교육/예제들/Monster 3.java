package com.greedy.section02.encapsulation.problem4;

public class Monster {
	private String kinds;
	private int hp;

	/* setter: private 필드에 값을 넣기 위한 용도 */
	public void setInfo(String info) {
		this.kinds = info;
	}
	
	
	public void setHp(int hp) {
		if(hp >= 0) {
			this.hp = hp;
		} else {
			this.hp = 0;
		}
	}
	
	/* getter : private 필드에 값을 불러오기 위한 용도 */
	public String getInfo() {
		return this.kinds ;				// this. 를 안 붙여도 this.를 적어준다.
	}
	
	public int getHp() {
		return this.hp;
	}
	
	/* 인스턴스가 가진 멤버 변수의 값들을 한 번에!! String 형태로 확인할 수 있게 반환하는 메소드 */
	public String getFields() {
		return "몬스터의 종류는 " + this.kinds + "이고, 체력은 " + hp;
	}
}
