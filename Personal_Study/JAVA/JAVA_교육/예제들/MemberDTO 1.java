package com.greedy.section03.filterstream.dto;

import java.io.Serializable;

/* Serializable은 Marker Interface로 직렬화 시 (객체 입출력)에 반드시 구현해야 한다. */
public class MemberDTO implements Serializable{
	
	/**
	 * 
	 */
	/* 
	 * serialVersionUID는 필수는 아니지만 클래스의 버전관리에 사용되며 출력하여 저장된 객체를 관리할 때 쓰이며 
	 * 클래스별로 겹치지 않는 값을 사용해야 하므로 왠만하면 수동으로 generated serial version ID로 UID를 생성하자.
	 */
	private static final long serialVersionUID = 3212239993131494960L;
	private String id;
	
	/* transient를 부여하면 직렬화 시에 해당 속성이 무시된다. */
//	private String pwd;
	private transient String pwd;
	
	private String name;
	private String email;
	private int age;
	private char gender;
	private double point;
	
	
	public MemberDTO() {
	}
	public MemberDTO(String id, String pwd, String name, String email, int age, char gender, double point) {
		this.id = id;
		this.pwd = pwd;
		this.name = name;
		this.email = email;
		this.age = age;
		this.gender = gender;
		this.point = point;
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

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
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

	public double getPoint() {
		return point;
	}

	public void setPoint(double point) {
		this.point = point;
	}

	@Override
	public String toString() {
		return "MemberDTO [id=" + id + ", pwd=" + pwd + ", name=" + name + ", email=" + email + ", age=" + age
				+ ", gender=" + gender + ", point=" + point + "]";
	}
}
