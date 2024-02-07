package com.greedy.section04.constructor.dto;

public class UserDTO {
	
	/*
	 * 자바 빈(Java Bean)이란?
	 * JSP에서 배우게 될 표준 액션 태그로 접근할 수 있는 자바 클래스이다.
	 * 자바 코드를 모르는 웹 퍼블리셔들도 자바 코드를 사용할 수 있도록 태그 형식으로 지원하는 문법을
	 * 의미하는데, 그 때 사용할 수 있도록 규칙을 지정해 놓은 java 클래스를 자바 빈(Java Bean)이라고 부른다.
	 * 
	 * 자바빈 작성 규칙
	 * 1. 자바빈은 특정 패키지에 속해 있어야 함.(default 패키지 사용 금지)
	 * 2. 멤버 변수의 접근 제어자는 private로 선언해야 함(캡슐화 적용)
	 * 3. 기본 생성자가 명시적으로 존재해야 한다.(매개변수 있는 생성자는 선택 사항)
	 * 4. 맴버 변수에 접근 가능한 설정자(setter)와 접근자(getter)가 public으로 작성되어 있어야 함.
	 * 5. 직렬화(Serializable 구현)가 되어야 한다. (선택사항) <-직렬화는 나중에 입출력에서 다루게 된다.
	 */
	
	/* 모든 필드를 private 접근 제한으로 설정하자. */
	private String id;
	private String pwd;
	private String name;
	private java.util.Date enrollDate;
	
	/* 기본 생성자를 명시적으로 작성하자. */
	public UserDTO() {}
	
	/* 
	 * 매개변수 있는 생성자는 선택사항이다. 
	 * 일반적으로 많이 사용하기 때문에 모든 필드의 값을 초기화 할 수 있는 생성자는 만든다.
	 */
	public UserDTO(String id, String pwd, String name, java.util.Date enrollDate) {
		this.id = id;
		this.pwd = pwd;
		this.name = name;
		this.enrollDate = enrollDate;
	}
	
	/* 설정자(setter) */
	public void setId(String id) {
		this.id = id;
	}
	public void setPwd(String pwd) {
		this.pwd = pwd;
	}
	public void setName(String name) {
		this.name = name;
	}
	public void setEnrollDate(java.util.Date enrollDate) {
		this.enrollDate = enrollDate;
	}
	
	/* 접근자(getter) */
	public String getId() {
		return this.id;
	}
	public String getPwd() {
		return this.pwd;
	}
	public String getName() {
		return this.name;
	}
	public java.util.Date getEnrollDate() {
		return this.enrollDate;
	}
	
	/* 
	 * 접근자(getter)로 하나씩 필드 값을 확인해 보기 번거롭기 때문에
	 * 모든 필드의 값을 하나의 문자열로 반환하는 메소드를 인스턴스의 필드값 확인용으로 많이 사용한다.
	 */
	public String getInformation() {
		return "id: " + this.id + ", pwd: " + this.pwd + ", name: " +this.name
		+ ", enrollDate: " + this.enrollDate;
	}

}