package com.greedy.section05.overloading.parameter;

public class Rectangle {
	private double width;
	private double height;
	
	public Rectangle() {
	}
	public Rectangle(double width, double height) {
		this.width = width;
		this.height = height;
	}
	
	public double getWidth() {
		return width;
	}
	public void setWidth(double width) {
		this.width = width;
	}
	public double getHeight() {
		return height;
	}
	public void setHeight(double height) {
		this.height = height;
	}

	@Override
	public String toString() {
		return "Rectangle [width=" + width + ", height=" + height + "]";
	}
	
	/* 사격형의 넓이 */
	public void calcArea() {
		double area = this.width * this.height;
		
		System.out.println("이 사격형의 넓이는 " + area + "입니다.");
	}
	
	/* 사각형의 둘레 */
	public void calcRound() {
		double round = (this.width + this.height) * 2;
		
		System.out.println("이 사격형의 둘레는 " + round + "입니다.");
	}
	
	
}
