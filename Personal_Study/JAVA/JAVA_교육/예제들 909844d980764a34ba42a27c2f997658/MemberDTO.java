package com.greedy.section02.uses;

public class MemberDTO {

	/* 관리 할 회원 정보를 추상화 해서 필드를 작성 */
	private int num;
	private String id;
	private String pwd;
	private String name;
	private int age;
	private char gender;
	
	/* 기본 생성자 */
	public MemberDTO() {
	}
	
	/* 모든 필드를 초기화 하는 매개변수 있는 생성자 */
	public MemberDTO(int num, String id, String pwd, String name, int age, char gender) {
		this.num = num;
		this.id = id;
		this.pwd = pwd;
		this.name = name;
		this.age = age;
		this.gender = gender;
	}
	
	/* getter & setter */
	public int getNum() {
		return num;
	}

	public void setNum(int num) {
		this.num = num;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getPwd() {
		return pwd;
	}

	public void setPwd(String pwd) {
		this.pwd = pwd;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getAge() {
		return age;
	}

	public void setAge(int age) {
		this.age = age;
	}

	public char getGender() {
		return gender;
	}

	public void setGender(char gender) {
		this.gender = gender;
	}

	/* toSting() */
	@Override
	public String toString() {
		return "MemberDTO [num=" + num + ", id=" + id + ", pwd=" + pwd + ", name=" + name + ", age=" + age + ", gender="
				+ gender + "]";
	}
	
}
